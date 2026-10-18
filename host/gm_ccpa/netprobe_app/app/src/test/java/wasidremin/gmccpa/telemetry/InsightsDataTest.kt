package wasidremin.gmccpa.telemetry

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [InsightsData] against fixture segments: report.json census reads plus an events.ndjson tail.
 * Framework-free like the code under test.
 */
class InsightsDataTest {
    private fun segment(
        root: File,
        name: String,
        batteryWh: Double?,
        speedMps: List<Double>,
        startNanos: Long = 1_000_000_000L,
    ): File {
        val dir = File(root, name).apply { mkdirs() }
        val headlines = """
            {"name":"EV_BATTERY_LEVEL","id":${InsightsData.BATTERY_WH},"units":"Wh (not percent)","result":"READABLE",
             "areas":[{"areaId":0,"initialRead":{"areaId":0,"result":"READABLE","status":0,"value":${batteryWh ?: "null"}}}]}
        """.trimIndent()
        val report = JSONObject()
            .put("captureStartWallTime", "$name-start")
            .put("captureStartElapsedRealtimeNanos", startNanos)
            .put("captureEndWallTime", "$name-end")
            .put("headlines", org.json.JSONArray("[$headlines]"))
            .put("subscriptionStatisticsByPropertyArea",
                JSONObject().put("${InsightsData.BATTERY_WH}:0", JSONObject().put("count", speedMps.size)))
        File(dir, "report.json").writeText(report.toString())
        File(dir, "events.ndjson").bufferedWriter().use { out ->
            speedMps.forEachIndexed { i, v ->
                out.write(JSONObject()
                    .put("wallTime", "$name-$i")
                    .put("elapsedRealtimeNanos", startNanos + i * 1_000_000_000L)
                    .put("kind", "PROPERTY")
                    .put("data", JSONObject()
                        .put("id", InsightsData.SPEED_MPS)
                        .put("areaId", 0).put("status", 0)
                        .put("timestampNanos", startNanos)
                        .put("value", v))
                    .toString())
                out.newLine()
            }
            // A bad-status sample and a non-numeric value must not poison the last value.
            out.write(JSONObject().put("kind", "PROPERTY")
                .put("elapsedRealtimeNanos", startNanos)
                .put("data", JSONObject().put("id", InsightsData.SPEED_MPS).put("status", 1).put("value", 999.0)).toString())
            out.newLine()
        }
        return dir
    }

    @Test fun lastValuesPreferLiveCallbacksOverCensus() {
        val root = createTempDir("insights")
        try {
            segment(root, "20261017-100000-000", batteryWh = 60000.0, speedMps = listOf(0.0, 13.9, 27.8))
            val segs = InsightsData.loadSegments(root)
            assertEquals(1, segs.size)
            val s = segs[0]
            assertEquals(27.8, s.lastValues[InsightsData.SPEED_MPS]!!, 1e-9)
            // Census fallback where nothing called back.
            assertEquals(60000.0, s.value(InsightsData.BATTERY_WH)!!, 1e-9)
            assertEquals(3L, s.events)
            val series = s.series[InsightsData.SPEED_MPS]!!
            assertEquals(3, series.size)
            assertEquals(2.0, series[2].tSec, 1e-9)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun trendSpansSegmentsOldestFirst() {
        val root = createTempDir("insights")
        try {
            segment(root, "20261017-100000-000", batteryWh = 60000.0, speedMps = listOf(1.0))
            segment(root, "20261017-110000-000", batteryWh = 55000.0, speedMps = listOf(2.0))
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
            segment(root, "20261017-130000-000", batteryWh = null, speedMps = emptyList())
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

    @Test fun formattingUsesWebsiteUnits() {
        assertEquals("60,000 Wh", InsightsData.formatBattery(60000.0))
        assertEquals("123 km", InsightsData.formatRange(123456.0))
        assertEquals("100 km/h", InsightsData.formatSpeed(27.8))
        assertEquals("21.5 °C", InsightsData.formatTemp(21.5))
        assertEquals("—", InsightsData.formatSpeed(null))
        assertTrue(InsightsData.formatCharge(-2500000.0).contains("-2.5 kW"))
    }
}
