package wasidremin.gmccpa.telemetry

import org.json.JSONObject
import java.io.File

/**
 * Reads packaged probe segments off disk into the shapes the in-app Insights page charts.
 *
 * A segment is one `<telemetry>/<yyyyMMdd-HHmmss-SSS>/` directory holding `report.json` (census
 * reads in `headlines[].areas[].initialRead.value`, per-property callback counts in
 * `subscriptionStatisticsByPropertyArea`) and `events.ndjson` (one JSON object per line; live
 * `PROPERTY` callbacks with `elapsedRealtimeNanos` and `data.{id,status,value}`). This mirrors
 * what the evinsights website draws: per-segment last values (its overview cards) and
 * within-segment time series (its Battery/Speed/Cabin charts) — at segment granularity rather
 * than the website's full-fidelity event stream.
 *
 * Framework-free (java.io + org.json only) so JVM unit tests cover it without Android.
 */
object InsightsData {
    /** Featured property ids, matching [ProbeModel.compileIds] names. */
    const val BATTERY_WH = 291504905          // EV_BATTERY_LEVEL, Wh (not percent)
    const val CHARGE_MW = 291504908           // EV_BATTERY_INSTANTANEOUS_CHARGE_RATE, mW
    const val RANGE_M = 291504904             // RANGE_REMAINING, m
    const val SPEED_MPS = 291504647           // PERF_VEHICLE_SPEED, m/s signed
    const val ODOMETER_KM = 291504644         // PERF_ODOMETER, km
    const val OUTSIDE_C = 291505923           // ENV_OUTSIDE_TEMPERATURE, Celsius
    const val GEAR = 289408000                // GEAR_SELECTION

    /** Ids drawn as within-segment time series on the Insights page. */
    val SERIES_IDS = listOf(BATTERY_WH, RANGE_M, SPEED_MPS, OUTSIDE_C, CHARGE_MW)

    /** Upper bound on points kept per series: stride-sampled, never truncated to a prefix. */
    const val MAX_POINTS = 2000
    /** Upper bound on event lines scanned per segment; the tail holds the latest values. */
    const val MAX_LINES = 60_000

    data class SeriesPoint(val tSec: Double, val value: Double)

    data class SegmentInsight(
        val name: String,
        val startWall: String,
        val endWall: String?,
        /** Total callbacks, summed from subscriptionStatisticsByPropertyArea. */
        val events: Long,
        /** Last numeric callback value per property id within this segment (all ids). */
        val lastValues: Map<Int, Double>,
        /** Census-time initial reads per property id (fallback when nothing called back). */
        val censusValues: Map<Int, Double>,
        /** Within-segment time series for [SERIES_IDS] only, seconds since segment start. */
        val series: Map<Int, List<SeriesPoint>>,
    ) {
        /** Chart/display value: live last callback wins, census read otherwise. */
        fun value(id: Int): Double? = lastValues[id] ?: censusValues[id]
    }

    /** All segments under [dataRoot], oldest first. Malformed segments are skipped, never fatal. */
    fun loadSegments(dataRoot: File): List<SegmentInsight> {
        val dirs = dataRoot.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            runCatching { loadSegment(dir) }.getOrNull()
        }
    }

    private fun loadSegment(dir: File): SegmentInsight? {
        val reportFile = File(dir, "report.json")
        if (!reportFile.isFile) return null
        val report = JSONObject(reportFile.readText())
        val startNanos = report.optLong("captureStartElapsedRealtimeNanos", -1L)
        val lastValues = mutableMapOf<Int, Double>()
        val series = mutableMapOf<Int, MutableList<SeriesPoint>>()
        for (id in SERIES_IDS) series[id] = mutableListOf()
        val eventsFile = File(dir, "events.ndjson")
        if (eventsFile.isFile && startNanos >= 0) {
            val lines = tailLines(eventsFile, MAX_LINES)
            val stride = (lines.size / MAX_POINTS).coerceAtLeast(1)
            for ((li, line) in lines.withIndex()) {
                val e = runCatching { JSONObject(line) }.getOrNull() ?: continue
                if (e.optString("kind") != "PROPERTY") continue
                val data = e.optJSONObject("data") ?: continue
                if (data.optInt("status", -1) != 0) continue
                val id = data.optInt("id", -1)
                val v = (data.opt("value") as? Number)?.toDouble()
                    ?.takeIf { it.isFinite() } ?: continue
                lastValues[id] = v
                if (id in series && li % stride == 0) {
                    val t = (e.optLong("elapsedRealtimeNanos", startNanos) - startNanos) / 1e9
                    series[id]!!.add(SeriesPoint(t.coerceAtLeast(0.0), v))
                }
            }
        }
        val census = mutableMapOf<Int, Double>()
        report.optJSONArray("headlines")?.let { headlines ->
            for (i in 0 until headlines.length()) {
                val h = headlines.optJSONObject(i) ?: continue
                val id = h.optInt("id", -1)
                val areas = h.optJSONArray("areas") ?: continue
                for (a in 0 until areas.length()) {
                    val read = areas.optJSONObject(a)?.optJSONObject("initialRead") ?: continue
                    if (read.optString("result") != "READABLE") continue
                    (read.opt("value") as? Number)?.toDouble()
                        ?.takeIf { it.isFinite() }?.let { census.putIfAbsent(id, it) }
                }
            }
        }
        var events = 0L
        report.optJSONObject("subscriptionStatisticsByPropertyArea")?.let { stats ->
            for (k in stats.keys()) events += stats.optJSONObject(k)?.optLong("count", 0L) ?: 0L
        }
        return SegmentInsight(
            name = dir.name,
            startWall = report.optString("captureStartWallTime", dir.name),
            endWall = report.optString("captureEndWallTime", null),
            events = events,
            lastValues = lastValues,
            censusValues = census,
            series = series.mapValues { it.value.toList() },
        )
    }

    /** Last [max] lines of [file]; the head holds stale chunks, the tail the latest values. */
    internal fun tailLines(file: File, max: Int): List<String> {
        if (max <= 0) return emptyList()
        val buf = ArrayDeque<String>(max)
        file.bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null) {
                if (buf.size >= max) buf.removeFirst()
                buf.addLast(line)
                line = reader.readLine()
            }
        }
        return buf.toList()
    }

    /** Per-segment last value of [id] across [segments]: the "over time" overview trend. */
    fun trend(segments: List<SegmentInsight>, id: Int): List<Pair<String, Double>> =
        segments.mapNotNull { s -> s.value(id)?.let { s.name to it } }

    // ---- display formatting (website units) ------------------------------------------------------

    fun formatBattery(wh: Double?): String = if (wh == null) "—" else "${"%,.0f".format(wh)} Wh"
    fun formatRange(m: Double?): String = if (m == null) "—" else "${"%,.0f".format(m / 1000)} km"
    fun formatSpeed(mps: Double?): String = if (mps == null) "—" else "${"%.0f".format(mps * 3.6)} km/h"
    fun formatTemp(c: Double?): String = if (c == null) "—" else "${"%.1f".format(c)} °C"
    fun formatCharge(mw: Double?): String = when {
        mw == null -> "—"
        mw >= 0 -> "+${"%.1f".format(mw / 1e6)} kW"
        else -> "${"%.1f".format(mw / 1e6)} kW"
    }
    fun formatOdometer(km: Double?): String = if (km == null) "—" else "${"%,.0f".format(km)} km"
}
