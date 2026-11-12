package wasidremin.gmccpa.telemetry

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.math.abs

/**
 * Not a test of the app: a tool that runs only when asked. It replays the vehicle reports a server
 * has stored through the car's own [TripRecorder], which gives the drive log the car would have
 * written had it been keeping one all along, and then checks [DriveOutlook] against those drives.
 *
 *     DRIVE_HISTORY_PROBES=/path/to/probes DRIVE_HISTORY_OUT=/path/out.ndjson \
 *         gradle :app:cleanTestPlayDebugUnitTest :app:testPlayDebugUnitTest --tests '*HistoryReplay*'
 *
 * The output carries no coordinates unless DRIVE_HISTORY_PLACES=1, so it can travel to the car over
 * a channel that is not private; such drives count toward everything except the Routes page.
 * `<out>.summary.txt` gets the counts and the estimate's error, numbers only.
 */
class HistoryReplayTest {
    private class Snap(val t0: Long, val distM: Double, val movingS: Double, val batteryWh: Double, val tempC: Double?,
                       val speedMps: Double, val drive: Trip)

    @Test fun replayStoredReportsIntoADriveLog() {
        val dir = System.getenv("DRIVE_HISTORY_PROBES")?.let { File(it) }
        val out = System.getenv("DRIVE_HISTORY_OUT")?.let { File(it) }
        assumeTrue("set DRIVE_HISTORY_PROBES and DRIVE_HISTORY_OUT to run", dir != null && out != null && dir.isDirectory)
        dir!!; out!!
        val places = System.getenv("DRIVE_HISTORY_PLACES") == "1"

        // One file per report name: a segment uploaded twice keeps its fullest copy.
        val files = dir.listFiles { f -> f.name.endsWith(".json") }!!.groupBy { it.name.substringAfter("_gmccpa_") }
            .toSortedMap().values.map { copies -> copies.maxBy { it.length() } }

        val trips = mutableListOf<Trip>()
        var tz = 0
        val r = TripRecorder(tzMin = { tz }) { trips += it }
        val snaps = mutableListOf<Snap>()
        var lastSnapT = 0L
        var lastT = 0L
        var events = 0L
        var skipped = 0
        val census = setOf(InsightsData.CAPACITY_WH, InsightsData.CHARGE_LIMIT_PCT, InsightsData.CHARGE_STATE)
        fun at(wall: String): Long? = runCatching {
            val o = OffsetDateTime.parse(wall)
            tz = o.offset.totalSeconds / 60
            o.toInstant().toEpochMilli()
        }.getOrNull()

        for (f in files) {
            val report = runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull()
            val fingerprint = report?.optString("fingerprint").orEmpty()
            if (report == null || fingerprint.contains("sdk_gcar") || fingerprint.contains("emulator")) { skipped++; continue }
            val start = at(report.optString("captureStartWallTime").ifEmpty { report.optString("startWallTime") })
            report.optJSONArray("headlines")?.let { hs ->
                for (i in 0 until hs.length()) {
                    val h = hs.optJSONObject(i) ?: continue
                    val read = h.optJSONArray("areas")?.optJSONObject(0)?.optJSONObject("initialRead") ?: continue
                    if (start != null && h.optInt("id") in census && read.optInt("status", -1) == 0 && !read.isNull("value"))
                        r.onProperty(start, h.optInt("id"), read.opt("value"))
                }
            }
            val evs = report.optJSONArray("rawLiveEvents") ?: continue
            for (i in 0 until evs.length()) {
                val e = evs.optJSONObject(i) ?: continue
                val t = at(e.optString("wallTime")) ?: continue
                val d = e.optJSONObject("data") ?: continue
                when (e.optString("kind")) {
                    "PROPERTY" -> if (d.optInt("status", -1) == 0 && !d.isNull("value")) r.onProperty(t, d.optInt("id"), d.opt("value"))
                    "LOCATION_RAW_PRIVACY_SENSITIVE" -> r.onLocation(t, d.optDouble("latitude"), d.optDouble("longitude"),
                        d.optDouble("accuracyMeters").takeIf { it.isFinite() }, d.optDouble("altitudeMeters").takeIf { it.isFinite() })
                    else -> continue
                }
                events++
                lastT = t
                if (abs(t - lastSnapT) >= 20_000) {
                    lastSnapT = t
                    val live = r.live(t)
                    val drive = live.drive
                    val b = live.batteryWh
                    if (drive != null && b != null) snaps += Snap(drive.t0, drive.distM, drive.movingS, b, live.tempC, live.speedMps, drive)
                }
            }
        }
        r.tick(lastT + 3_600_000L)

        val drives = trips.filter { it.kind == Trip.Kind.DRIVE }.sortedBy { it.t0 }
        val charges = trips.filter { it.kind == Trip.Kind.CHARGE }
        out.parentFile?.mkdirs()
        out.writeText(trips.sortedBy { it.t0 }.joinToString("") { t ->
            (if (places) t else t.copy(lat0 = null, lon0 = null, lat1 = null, lon1 = null)).toJson().toString() + "\n"
        }, Charsets.UTF_8)

        // DRIVE_HISTORY_SNAPS=<file>: every 20 s of every drive, for working on the estimate offline.
        System.getenv("DRIVE_HISTORY_SNAPS")?.let { path ->
            File(path).writeText(snaps.joinToString("") { s ->
                JSONObject().put("t0", s.t0).put("dist", s.distM).put("moving", s.movingS).put("b", s.batteryWh)
                    .putOpt("temp", s.tempC).put("net", s.drive.netWh).put("warmM", s.drive.warmM).put("warmWh", s.drive.warmWh)
                    .put("standWh", s.drive.standWh).put("t", s.drive.t1).toString() + "\n"
            })
        }

        // How the estimate would have done: at the start and at each quarter of every longer
        // drive, with only the drives before it as history and the rest of the drive's own distance
        // and moving time standing in for what the phone would have said.
        val cap = trips.lastOrNull { it.capWh != null }?.capWh ?: 85_000.0
        val byDrive = snaps.groupBy { it.t0 }
        class Err(val points: Double, val rel: Double, val naivePoints: Double, val naiveRel: Double, val basis: DriveOutlook.Basis)
        val errs = linkedMapOf(0.0 to mutableListOf<Err>(), 0.25 to mutableListOf(), 0.5 to mutableListOf(), 0.75 to mutableListOf())
        drives.forEachIndexed { n, d ->
            if (n < 10 || d.distM < 8000.0 || d.movingS <= 0.0) return@forEachIndexed
            val before = drives.subList(0, n)
            val h = DriveOutlook.History(before)
            val typical = TripStats.median(before.mapNotNull { TripStats.whPerKm(it) }) ?: return@forEachIndexed
            for ((share, list) in errs) {
                val s = if (share == 0.0) null else byDrive[d.t0]?.firstOrNull { it.distM >= share * d.distM } ?: continue
                val driven = s?.distM ?: 0.0
                val battery = s?.batteryWh ?: d.b0
                val ahead = d.distM - driven
                val aheadS = d.movingS - (s?.movingS ?: 0.0)
                if (ahead < 2000.0 || aheadS <= 0.0) continue
                val o = DriveOutlook.outlook(DriveOutlook.Input(s?.drive, battery, cap, s?.tempC ?: d.tempC, s?.speedMps ?: 0.0, ahead, aheadS), h)
                val actual = battery - d.b1
                val predicted = o.arrival!!.energyWh
                val naive = typical * ahead / 1000.0
                if (actual <= 0.0) continue
                list += Err((predicted - actual) / cap * 100, (predicted - actual) / actual,
                    (naive - actual) / cap * 100, (naive - actual) / actual, o.baseline.basis)
            }
        }
        fun med(v: List<Double>) = TripStats.median(v) ?: Double.NaN
        fun f(v: Double) = "%.1f".format(Locale.US, v)
        val sb = StringBuilder()
        sb.appendLine("reports read ${files.size - skipped} (skipped $skipped), events $events")
        sb.appendLine("drives ${drives.size}, charges ${charges.size}, usable for the estimate ${drives.count { DriveOutlook.sample(it) != null }}")
        sb.appendLine("total ${f(drives.sumOf { it.distM } / 1000)} km; drives of 8 km or more: ${drives.count { it.distM >= 8000 }}")
        val ks = drives.mapNotNull { DriveOutlook.sample(it) }
        sb.appendLine("k: median ${"%.2f".format(Locale.US, med(ks.map { it.k }))}, range ${"%.2f".format(Locale.US, ks.minOfOrNull { it.k } ?: 0.0)}" +
            "..${"%.2f".format(Locale.US, ks.maxOfOrNull { it.k } ?: 0.0)}; warm-up median ${f(med(ks.map { it.warmupWh }))} Wh; " +
            "outside temperature ${f(drives.mapNotNull { it.tempC }.minOrNull() ?: 0.0)}..${f(drives.mapNotNull { it.tempC }.maxOrNull() ?: 0.0)} C; " +
            "moving speed median ${f(med(ks.map { it.speedMps }))} m/s")
        val hAll = DriveOutlook.History(drives)
        sb.appendLine("repeated routes found: ${hAll.index.repeated.size}")
        for ((share, list) in errs) {
            if (list.isEmpty()) continue
            sb.appendLine("at ${(share * 100).toInt()}% of the drive (n=${list.size}): energy ahead off by a median of " +
                "${f(med(list.map { abs(it.rel) }) * 100)}% (bias ${f(med(list.map { it.rel }) * 100)}%), " +
                "${f(med(list.map { abs(it.points) }))} points of the pack, worst ${f(list.maxOf { abs(it.points) })}; " +
                "flat average instead: ${f(med(list.map { abs(it.naiveRel) }) * 100)}% and ${f(med(list.map { abs(it.naivePoints) }))} points, " +
                "worst ${f(list.maxOf { abs(it.naivePoints) })}; basis ${list.groupingBy { it.basis }.eachCount()}")
        }
        File(out.path + ".summary.txt").writeText(sb.toString())
    }
}
