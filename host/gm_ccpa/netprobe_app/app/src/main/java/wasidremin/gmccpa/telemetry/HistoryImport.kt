package wasidremin.gmccpa.telemetry

import android.content.Context
import org.json.JSONObject
import wasidremin.gmccpa.ProbeLog
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fills the drive log ([TripStore]) with the drives from before the car kept one.
 *
 * The log began with Play 245. The receiver holds the vehicle reports from the weeks before, and
 * `HistoryReplayTest` turns them into the records the car would have written, using the car's own
 * [TripRecorder]. The receiver offers that file at `GET <base>/history?car=<id>`; this fetches it
 * once, in the background, after a telemetry upload has succeeded, and appends the records that do
 * not overlap anything the car recorded itself.
 *
 * The receiver has no login, so two things keep the file to this car. It carries no coordinates:
 * imported drives count toward the Week page, the charging costs and the Drive card's normal, and
 * stay off the Routes page, which the car's own records fill. And the receiver answers only a
 * caller that names the id of a capture's unchanging part ([ProbeSlim.staticId]), which it has
 * stored and does not list, so only a car that has uploaded there can ask. It is a few kilobytes,
 * so it does not wait for Wi-Fi.
 */
object HistoryImport {
    private val log by lazy { ProbeLog.sub("probe") }
    private const val PREFS = "insights_history"
    private const val KEY_DONE_AT = "imported_at"
    private const val KEY_ADDED = "imported_records"
    private val tried = AtomicBoolean(false)

    fun endpointFor(baseUrl: String, carId: String): String = baseUrl.trim().trimEnd('/') + "/history?car=" + carId

    /** Pure: one [Trip] per line; lines that are not records are skipped. */
    fun parse(text: String): List<Trip> = text.lineSequence().mapNotNull { line ->
        if (line.isBlank()) null else runCatching { Trip.fromJson(JSONObject(line)) }.getOrNull()
    }.toList()

    /**
     * Pure: the records of [incoming] worth adding to [existing]. A record is left out when it
     * overlaps one of the same kind already there (the car recorded that drive itself) or an
     * earlier one of its own file.
     */
    fun fresh(existing: List<Trip>, incoming: List<Trip>): List<Trip> {
        val kept = existing.toMutableList()
        val out = mutableListOf<Trip>()
        for (t in incoming.sortedBy { it.t0 }) {
            if (t.open || t.t1 < t.t0) continue
            if (kept.any { it.kind == t.kind && it.t0 <= t.t1 && t.t0 <= it.t1 }) continue
            kept += t
            out += t
        }
        return out
    }

    fun done(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_DONE_AT, 0L) != 0L

    /** How many records the import added, or null when it has not run. */
    fun added(ctx: Context): Int? = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .takeIf { it.getLong(KEY_DONE_AT, 0L) != 0L }?.getInt(KEY_ADDED, 0)

    /**
     * Once per install, and at most one attempt per start of the app: a receiver without the file
     * answers 404. [carId] is the [ProbeSlim.staticId] of a report the receiver has just accepted.
     */
    fun runOnce(ctx: Context, baseUrl: String, carId: String) {
        val app = ctx.applicationContext
        if (done(app) || !tried.compareAndSet(false, true)) return
        if (!baseUrl.trim().startsWith("https://", ignoreCase = true) || !carId.matches(Regex("[0-9a-f]{16}"))) return
        Thread({
            try {
                val c = (URL(endpointFor(baseUrl, carId)).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 30_000
                    setRequestProperty("Accept", "application/x-ndjson")
                    setRequestProperty("X-Device", "gmccpa")
                }
                try {
                    val code = c.responseCode
                    if (code != 200) { log.i("drive history: the receiver has none to give (HTTP $code); asking again at the next start"); return@Thread }
                    val text = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val store = TripStore(TripStore.dirUnder(app.getExternalFilesDir(null) ?: app.filesDir))
                    val existing = store.load()
                    val incoming = parse(text)
                    val add = fresh(existing, incoming)
                    add.forEach { store.append(it) }
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putLong(KEY_DONE_AT, System.currentTimeMillis()).putInt(KEY_ADDED, add.size).apply()
                    log.i("drive history: ${incoming.size} records offered (${text.length} B), ${add.size} added " +
                        "(${add.count { it.kind == Trip.Kind.DRIVE }} drives, ${add.count { it.kind == Trip.Kind.CHARGE }} charges), " +
                        "${existing.size} were already in the car's log")
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                log.i("drive history: not fetched (${e.javaClass.simpleName}); asking again at the next start")
            }
        }, "drive-history-import").apply { isDaemon = true }.start()
    }
}
