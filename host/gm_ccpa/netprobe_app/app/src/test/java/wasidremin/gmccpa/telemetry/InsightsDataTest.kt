package wasidremin.gmccpa.telemetry

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wasidremin.gmccpa.telemetry.InsightsData.UnitSystem
import java.io.File

/**
 * [InsightsData] against fixture segments: report.json census reads, WHEEL_TICK / speed / power
 * integration, session grouping by `bootId`, efficiency (`Wh/km` & `mi/kWh`), and unit formatting.
 * Framework-free like the code under test.
 */
class InsightsDataTest {
    private fun segment(
        root: File,
        name: String,
        bootId: String = "boot-1",
        batteryStartWh: Double? = null,
        batteryEndWh: Double? = null,
        speedMps: List<Double> = emptyList(),
        wheelTicks: List<Long> = emptyList(),
        powerMw: List<Double> = emptyList(),
        rangeStartM: Double? = null,
        rangeEndM: Double? = null,
        startNanos: Long = 1_000_000_000L,
        useEmbeddedEventsOnly: Boolean = false,
    ): File {
        val dir = File(root, name).apply { mkdirs() }
        val headlines = JSONArray().apply {
            put(JSONObject()
                .put("name", "EV_BATTERY_LEVEL")
                .put("id", InsightsData.BATTERY_WH)
                .put("units", "Wh (not percent)")
                .put("result", "READABLE")
                .put("areas", JSONArray().put(JSONObject()
                    .put("areaId", 0)
                    .put("initialRead", JSONObject()
                        .put("areaId", 0).put("result", "READABLE").put("status", 0)
                        .put("value", batteryStartWh ?: JSONObject.NULL)))))
            put(JSONObject()
                .put("name", "INFO_EV_BATTERY_CAPACITY")
                .put("id", InsightsData.CAPACITY_WH)
                .put("units", "Wh (not percent)")
                .put("result", "READABLE")
                .put("areas", JSONArray().put(JSONObject()
                    .put("areaId", 0)
                    .put("initialRead", JSONObject()
                        .put("areaId", 0).put("result", "READABLE").put("status", 0)
                        .put("value", 84590.0)))))
            put(JSONObject()
                .put("name", "INFO_MODEL")
                .put("id", InsightsData.INFO_MODEL)
                .put("result", "READABLE")
                .put("areas", JSONArray().put(JSONObject()
                    .put("areaId", 0)
                    .put("initialRead", JSONObject()
                        .put("areaId", 0).put("result", "READABLE").put("status", 0)
                        .put("value", "C223")))))
        }
        val properties = JSONArray().put(
            JSONObject()
                .put("id", InsightsData.WHEEL_TICK)
                .put("name", "WHEEL_TICK")
                .put("configurationArray", JSONArray(listOf(15, 24145, 24145, 24145, 24145)))
                .put("areas", JSONArray())
        )

        val rawLines = mutableListOf<JSONObject>()
        if (batteryStartWh != null) {
            rawLines += propEvent(startNanos, InsightsData.BATTERY_WH, batteryStartWh)
        }
        if (rangeStartM != null) {
            rawLines += propEvent(startNanos, InsightsData.RANGE_M, rangeStartM)
        }
        speedMps.forEachIndexed { i, v ->
            rawLines += propEvent(startNanos + i * 1_000_000_000L, InsightsData.SPEED_MPS, v)
        }
        wheelTicks.forEachIndexed { i, t ->
            rawLines += propEvent(
                startNanos + i * 1_000_000_000L,
                InsightsData.WHEEL_TICK,
                JSONArray(listOf(0L, t, t, t, t)),
            )
        }
        powerMw.forEachIndexed { i, p ->
            rawLines += propEvent(startNanos + i * 1_000_000_000L, InsightsData.CHARGE_MW, p)
        }
        val lastNanos = startNanos + maxOf(speedMps.size, wheelTicks.size, powerMw.size, 1) * 1_000_000_000L
        if (batteryEndWh != null) {
            rawLines += propEvent(lastNanos, InsightsData.BATTERY_WH, batteryEndWh)
        }
        if (rangeEndM != null) {
            rawLines += propEvent(lastNanos, InsightsData.RANGE_M, rangeEndM)
        }
        // A bad-status sample must not poison the last value.
        rawLines += JSONObject()
            .put("kind", "PROPERTY")
            .put("elapsedRealtimeNanos", lastNanos)
            .put("data", JSONObject().put("id", InsightsData.SPEED_MPS).put("status", 1).put("value", 999.0))

        val report = JSONObject()
            .put("bootId", bootId)
            .put("captureStartWallTime", "2026-10-19T14:20:00.000-04:00")
            .put("captureStartElapsedRealtimeNanos", startNanos)
            .put("captureEndWallTime", "2026-10-19T14:21:00.000-04:00")
            .put("captureEndElapsedRealtimeNanos", lastNanos)
            .put("headlines", headlines)
            .put("properties", properties)
            .put("subscriptionStatisticsByPropertyArea",
                JSONObject().put("${InsightsData.SPEED_MPS}:0", JSONObject().put("callbacks", speedMps.size)))

        if (useEmbeddedEventsOnly) {
            val arr = JSONArray()
            rawLines.forEach { arr.put(it) }
            report.put("rawLiveEvents", arr)
        } else {
            File(dir, "events.ndjson").bufferedWriter().use { out ->
                for (obj in rawLines) {
                    out.write(obj.toString())
                    out.newLine()
                }
            }
        }
        File(dir, "report.json").writeText(report.toString())
        return dir
    }

    private fun propEvent(nanos: Long, id: Int, value: Any): JSONObject =
        JSONObject()
            .put("wallTime", "2026-10-19T14:20:00.000-04:00")
            .put("elapsedRealtimeNanos", nanos)
            .put("kind", "PROPERTY")
            .put("data", JSONObject()
                .put("id", id)
                .put("areaId", 0)
                .put("status", 0)
                .put("timestampNanos", nanos)
                .put("value", value))

    @Test fun lastValuesPreferLiveCallbacksOverCensus() {
        val root = createTempDir("insights")
        try {
            segment(root, "20261017-100000-000", batteryStartWh = 60000.0, speedMps = listOf(0.0, 13.9, 27.8))
            val segs = InsightsData.loadSegments(root)
            assertEquals(1, segs.size)
            val s = segs[0]
            assertEquals(27.8, s.lastValues[InsightsData.SPEED_MPS]!!, 1e-9)
            assertEquals(60000.0, s.value(InsightsData.BATTERY_WH)!!, 1e-9)
            assertEquals("2025 Chevrolet Equinox EV (C223)", s.vehicleProfile.displayTitle)
            val series = s.series[InsightsData.SPEED_MPS]!!
            assertEquals(3, series.size)
            assertEquals(2.0, series[2].tSec, 1e-9)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun embeddedRawLiveEventsWorkWithoutNdjsonFile() {
        val root = createTempDir("insights-embedded")
        try {
            segment(
                root,
                "20261017-100000-000",
                batteryStartWh = 67000.0,
                batteryEndWh = 65000.0,
                speedMps = listOf(10.0, 20.0, 20.0),
                wheelTicks = listOf(0L, 10_000L, 50_000L),
                useEmbeddedEventsOnly = true,
            )
            val segs = InsightsData.loadSegments(root)
            assertEquals(1, segs.size)
            val s = segs[0]
            assertEquals(65000.0, s.value(InsightsData.BATTERY_WH)!!, 1e-9)
            // 50,000 ticks * 24,145 um/tick = 1,207.25 m
            assertEquals(1207.25, s.distanceWheelM!!, 0.01)
            assertEquals("WHEEL_TICK", s.preferredDistanceSource)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun sessionGroupingDerivesDriveEfficiencyAndRegen() {
        val root = createTempDir("insights-session")
        try {
            // Segment 1: 0 -> 100,000 ticks (2,414.5 m), 67,000 -> 66,500 Wh
            segment(
                root,
                "20261019-140000-000",
                bootId = "drive-boot",
                batteryStartWh = 67000.0,
                batteryEndWh = 66500.0,
                speedMps = listOf(15.0, 20.0, 20.0),
                wheelTicks = listOf(0L, 50_000L, 100_000L),
                powerMw = listOf(-36_000_000.0, -36_000_000.0, 18_000_000.0),
                rangeStartM = 420_000.0,
                rangeEndM = 417_500.0,
                startNanos = 10_000_000_000L,
            )
            // Segment 2: 100,000 -> 200,000 ticks (+2,414.5 m = 4,829.0 m total), 66,500 -> 66,000 Wh (1,000 Wh net)
            segment(
                root,
                "20261019-140100-000",
                bootId = "drive-boot",
                batteryStartWh = 66500.0,
                batteryEndWh = 66000.0,
                speedMps = listOf(20.0, 25.0, 0.0),
                wheelTicks = listOf(100_000L, 150_000L, 200_000L),
                powerMw = listOf(-36_000_000.0, 18_000_000.0, 0.0),
                rangeStartM = 417_500.0,
                rangeEndM = 415_000.0,
                startNanos = 70_000_000_000L,
            )
            val segs = InsightsData.loadSegments(root)
            val fleet = InsightsData.summarize(segs)
            assertEquals(1, fleet.sessions.size)
            assertEquals(1, fleet.drives.size)
            val drive = fleet.drives.first()
            assertEquals(InsightsData.SessionKind.DRIVE, drive.kind)
            assertEquals(4829.0, drive.preferredDistanceM!!, 0.1)
            assertEquals(1000.0, drive.netEnergyWh!!, 0.1)
            // 1000 Wh / 4.829 km = 207.08 Wh/km -> ~3.00 mi/kWh
            assertNotNull(drive.whPerKm)
            assertEquals(207.08, drive.whPerKm!!, 0.5)
            assertEquals(3.00, drive.miPerKwh!!, 0.05)
            assertTrue(drive.grossDischargeWh > 0.0)
            assertTrue(drive.grossRegenWh > 0.0)
            assertEquals(1, drive.stopCount)
            // GOM used 5,000 m while actual driven was 4,829 m -> ratio ~0.9658
            assertEquals(0.9658, drive.rangeEfficiencyRatio!!, 0.01)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun trendSpansSegmentsOldestFirst() {
        val root = createTempDir("insights")
        try {
            segment(root, "20261017-100000-000", batteryStartWh = 60000.0, speedMps = listOf(1.0))
            segment(root, "20261017-110000-000", batteryStartWh = 55000.0, speedMps = listOf(2.0))
            val segs = InsightsData.loadSegments(root)
            assertEquals(listOf("20261017-100000-000", "20261017-110000-000"), segs.map { it.name })
            val trend = InsightsData.trend(segs, InsightsData.BATTERY_WH)
            assertEquals(60000.0, trend[0].second, 1e-9)
            assertEquals(55000.0, trend[1].second, 1e-9)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun malformedSegmentsAreSkipped() {
        val root = createTempDir("insights")
        try {
            File(root, "20261017-120000-000").apply { mkdirs() }
                .resolve("report.json").writeText("{not json")
            segment(root, "20261017-130000-000", batteryStartWh = null, speedMps = emptyList())
            val segs = InsightsData.loadSegments(root)
            assertEquals(1, segs.size)
            assertEquals("20261017-130000-000", segs[0].name)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun tailLinesKeepsTheTail() {
        val f = createTempFile("tail", ".ndjson")
        try {
            f.bufferedWriter().use { out -> repeat(10) { out.write("l$it\n") } }
            assertEquals(listOf("l7", "l8", "l9"), InsightsData.tailLines(f, 3))
            assertEquals(10, InsightsData.tailLines(f, 100).size)
        } finally {
            f.delete()
        }
    }

    @Test fun formattingSupportsMetricAndImperialUnits() {
        assertEquals("60,000 Wh", InsightsData.formatBattery(60000.0))
        assertEquals("79.9% (67.6 kWh)", InsightsData.formatBatteryWithSoc(67590.0, 84590.0))
        assertEquals("123 km", InsightsData.formatRange(123456.0, UnitSystem.METRIC))
        assertEquals("77 mi", InsightsData.formatRange(123456.0, UnitSystem.IMPERIAL))
        assertEquals("100 km/h", InsightsData.formatSpeed(27.8, UnitSystem.METRIC))
        assertEquals("62 mph", InsightsData.formatSpeed(27.8, UnitSystem.IMPERIAL))
        assertEquals("20.0 °C", InsightsData.formatTemp(20.0, UnitSystem.METRIC))
        assertEquals("68 °F", InsightsData.formatTemp(20.0, UnitSystem.IMPERIAL))
        assertEquals("200 Wh/km", InsightsData.formatEfficiency(200.0, UnitSystem.METRIC))
        assertEquals("3.11 mi/kWh", InsightsData.formatEfficiency(200.0, UnitSystem.IMPERIAL))
        assertEquals("—", InsightsData.formatSpeed(null))
        assertTrue(InsightsData.formatCharge(-2500000.0).contains("-2.5 kW"))
        assertEquals("Oct 19 · 14:21", InsightsData.formatWallShort("2026-10-19T14:21:03.758-04:00"))
    }
}
