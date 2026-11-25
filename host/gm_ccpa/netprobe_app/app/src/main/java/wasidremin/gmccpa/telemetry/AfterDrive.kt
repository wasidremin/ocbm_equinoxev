package wasidremin.gmccpa.telemetry

import android.content.Context
import wasidremin.gmccpa.ProbeLog
import java.util.concurrent.Executors

/**
 * When the after-drive summary ([DriveSummary], T28) shows: the gear comes from
 * `VehicleStateWatcher`, the drive from [DriveLive], the history from the drive log; the CarPlay
 * screen draws [showing] and is told through [listener].
 */
object AfterDrive {
    private const val PREFS = "after_drive"
    private const val KEY_ENABLED = "enabled"

    private val log by lazy { ProbeLog.sub("cluster") }
    private val io by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "after-drive").apply { isDaemon = true } } }

    /** Settings ▸ Screen & Sound ▸ After-drive summary. On by default (owner, 2026-11-24: asked for it). */
    @Volatile var enabled: Boolean = true
        private set

    /** The summary on screen, and until when (wall clock); null when none. */
    @Volatile var showing: DriveSummary.Summary? = null
        private set
    @Volatile var untilMs: Long = 0L
        private set

    /** Main thread or not; the CarPlay screen posts to itself. */
    @Volatile var listener: (() -> Unit)? = null

    @Volatile private var wasParked: Boolean? = null
    @Volatile private var shownT0: Long? = null
    @Volatile private var dir: java.io.File? = null

    fun load(ctx: Context) {
        val app = ctx.applicationContext
        enabled = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
        dir = TripStore.dirUnder(app.getExternalFilesDir(null) ?: app.filesDir)
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        enabled = on
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        if (!on) dismiss("turned off")
    }

    fun statusText(): String = if (enabled) {
        "On. When you shift into Park after a drive of a mile or more, the drive shows over CarPlay for 30 s: " +
            "mi/kWh against your usual, where the energy went, your last 10 drives and the month. A tap closes it."
    } else {
        "Off. Nothing shows on Park."
    }

    /** The gear, as `VehicleStateWatcher` reads it: true in Park, false in any other gear; unknown readings are not passed. */
    fun onGear(parked: Boolean) {
        val was = wasParked
        wasParked = parked
        if (!parked) { dismiss("left Park"); return }
        if (!DriveSummary.parkedAfterDriving(was, parked) || !enabled) return
        val now = System.currentTimeMillis()
        val live = DriveLive.fresh(now)
        val drive = live?.drive
        if (!DriveSummary.worthShowing(drive, shownT0)) {
            log.i("drive summary: not shown (${if (drive == null) "no drive" else "${drive.distM.toInt()} m"})")
            return
        }
        shownT0 = drive!!.t0
        val cap = live.capWh
        val folder = dir
        io.execute {
            runCatching {
                val stored = folder?.let { TripStore(it).load() }.orEmpty()
                val tz = java.util.TimeZone.getDefault().getOffset(now) / 60_000
                val s = DriveSummary.of(drive, stored, cap, DriveOutlook.monthStart(now, tz))
                // Out of Park while the log was read: too late.
                if (wasParked != true) return@runCatching
                showing = s
                untilMs = System.currentTimeMillis() + DriveSummary.SHOW_MS
                log.i(DriveSummary.logLine(s))
                listener?.invoke()
            }.onFailure { log.w("drive summary: failed (${it.javaClass.simpleName})") }
        }
    }

    /** Takes the summary down; [why] goes in the log. */
    fun dismiss(why: String) {
        if (showing == null) return
        showing = null
        untilMs = 0L
        log.i("drive summary: closed ($why)")
        listener?.invoke()
    }

    /** Shows [s] as if the car had just parked: the CarPlay screen's `summary_preview`. */
    fun preview(s: DriveSummary.Summary) {
        showing = s
        untilMs = System.currentTimeMillis() + DriveSummary.SHOW_MS
        listener?.invoke()
    }
}
