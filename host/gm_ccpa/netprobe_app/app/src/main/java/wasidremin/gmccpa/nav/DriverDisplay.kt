package wasidremin.gmccpa.nav

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import wasidremin.gmccpa.ProbeLog
import wasidremin.gmccpa.ocbm.PixelSize

/**
 * CarPlay's instrument-cluster map on the car's driver display (Settings ▸ Navigation ▸ CarPlay map
 * on the driver display, off by default).
 *
 * The idea is openautolink's (mossyhub/openautolink, "driver-display maps", 0.1.575, validated on a
 * 2024 Blazer EV): GM shows the driver display to Android as an ordinary public display, so an app may
 * open its own window there, with no cluster API and no privilege, and draw the phone's cluster video
 * into it. Written here from their description; none of their code (GPL-3.0) is used.
 *
 * Our half is small because the adapter already does the CarPlay part: given `altVideoStreams` it
 * advertises Apple's type-111 cluster display, iOS sets the stream up by itself, and the box forwards
 * it on `CH_ALT_VIDEO` once `CMD_NAV_START` asks it to (`docs/carplay/06_AV_PIPELINE.md`). So:
 *  1. [declare], at every subscribe: logs every display Android shows this app, setting on or off, so
 *     one drive answers whether the car has a usable one; with the setting on, the largest non-default
 *     public display this app may start an activity on is declared as the cluster screen;
 *  2. [show], once CarPlay's picture is up: [DriverDisplayActivity] opens on that display and
 *     `AdapterSession.attachCluster` decodes the stream into it.
 *
 * What their owner docs say to expect on GM, and so do we until the car says otherwise: the driver
 * picks the maps page on the driver display; GM's own turn icons and head-up directions give way while
 * the map shows; the first time, the head unit may need a restart (End Call held ~30 s while parked);
 * and the picture can come back black after a page change.
 */
object DriverDisplay {
    private val log = ProbeLog.sub("cluster")

    private const val PREFS = "driver_display"
    private const val KEY_ENABLED = "enabled"
    /** Window launches per session at most, so a window GM keeps closing cannot become a loop. */
    private const val LAUNCHES_PER_SESSION = 3

    /** A display this app may open a window on. */
    data class Found(val id: Int, val name: String, val width: Int, val height: Int)

    @Volatile var enabled = false
        private set
    @Volatile private var loaded = false

    /** This session's driver display and the cluster screen size declared for it; null for none. */
    @Volatile var display: Found? = null
        private set
    @Volatile var size: PixelSize? = null
        private set
    @Volatile private var launches = 0
    @Volatile private var firstFrameLogged = false

    fun load(ctx: Context) {
        if (loaded) return
        enabled = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
        loaded = true
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        load(ctx)
        enabled = on
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        log.i("driver display map ${if (on) "ON" else "off"} (Settings; applies at the next connect)")
        ClusterNav.onDriverDisplayChanged()
        if (!on) DriverDisplayActivity.closeOpen("turned off in Settings")
    }

    /** What Settings shows under the switch. */
    fun statusText(ctx: Context): String {
        load(ctx)
        val found = runCatching { find(ctx, logAll = false) }.getOrNull()
        val where = if (found == null) "No driver display this app may open a window on was found."
            else "Driver display found: ${found.width}×${found.height}."
        return "Experimental. CarPlay's Maps on the driver display, from the next connect. GM's own map can take " +
            "the display back; long-press the glass right of CarPlay to show CarPlay's again. Turn-by-turn in the " +
            "cluster pauses while this is on. $where"
    }

    /**
     * Every display Android shows this app, and the one a window can go on: not the main screen, not
     * private, and starting [DriverDisplayActivity] there allowed. The largest wins when there are
     * several (a head-up display would be the smaller one).
     */
    fun find(ctx: Context, logAll: Boolean): Found? {
        val dm = ctx.getSystemService(DisplayManager::class.java) ?: return null
        val am = ctx.getSystemService(ActivityManager::class.java)
        val probe = Intent(ctx, DriverDisplayActivity::class.java)
        val usable = ArrayList<Found>()
        for (d in dm.displays) {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") d.getRealMetrics(m)
            val main = d.displayId == Display.DEFAULT_DISPLAY
            val private = d.flags and Display.FLAG_PRIVATE != 0
            val allowed = !main && !private &&
                runCatching { am?.isActivityStartAllowedOnDisplay(ctx, d.displayId, probe) == true }.getOrDefault(false)
            if (logAll) {
                log.i("display ${d.displayId} \"${d.name}\" ${m.widthPixels}x${m.heightPixels} ${m.densityDpi}dpi " +
                    "flags=0x${Integer.toHexString(d.flags)} state=${stateName(d.state)} " +
                    when { main -> "(main screen)"; private -> "(private)"; else -> "window ${if (allowed) "allowed" else "refused"}" })
            }
            if (allowed) usable += Found(d.displayId, d.name ?: "", m.widthPixels, m.heightPixels)
        }
        if (usable.size > 1 && logAll) log.i("${usable.size} displays allow a window; taking the largest")
        return usable.maxByOrNull { it.width.toLong() * it.height }
    }

    /**
     * At subscribe: the cluster screen to declare, or null. Logs the displays either way. The size is
     * the display's, rounded down to even as every coded size must be.
     */
    fun declare(ctx: Context): PixelSize? {
        load(ctx)
        // Runs inside every subscribe: a display query that throws must cost the map, not CarPlay.
        val found = runCatching { find(ctx, logAll = true) }
            .onFailure { log.w("display query failed: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()
        if (!enabled) return null
        if (found == null) {
            log.w("driver display map is on, but no display allows a window — not declared")
            return null
        }
        val w = found.width and 1.inv()
        val h = found.height and 1.inv()
        if (w !in 2..3840 || h !in 2..1600) {
            log.w("driver display ${found.width}x${found.height} is outside 3840x1600 — not declared")
            return null
        }
        display = found
        return PixelSize(w, h)
    }

    /** After subscribe: [declared] is what the document that went out really carried. Any thread. */
    fun onSubscribe(declared: PixelSize?) {
        size = declared
        launches = 0
        firstFrameLogged = false
        if (declared == null) display = null
        else log.i("declared the driver display ${display?.id} as CarPlay's cluster screen, ${declared.width}x${declared.height}")
    }

    /**
     * Main thread. The owner asked for the map back (a long-press on the strip right of CarPlay):
     * open the window again after GM took the display back, at most once every two seconds.
     */
    fun reopen(from: Context, why: String) {
        if (display == null || size == null) {
            log.i("map not reopened ($why) — ${if (!enabled) "the setting is off" else "no driver display this session"}")
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastReopenAt < 2_000L) return
        lastReopenAt = now
        launches = 0
        show(from, why)
    }
    @Volatile private var lastReopenAt = 0L

    /** Main thread. Open the window on the driver display unless it is already open. */
    fun show(from: Context, why: String) {
        val d = display ?: return
        if (size == null || DriverDisplayActivity.isOpen()) return
        if (launches >= LAUNCHES_PER_SESSION) return
        launches++
        val intent = Intent(from, DriverDisplayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            val am = from.getSystemService(ActivityManager::class.java)
            if (am?.isActivityStartAllowedOnDisplay(from, d.id, intent) != true) {
                log.w("window on display ${d.id} refused now ($why)")
                return
            }
            from.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(d.id).toBundle())
            log.i("opening the map window on display ${d.id} ($why, launch $launches of $LAUNCHES_PER_SESSION)")
        } catch (e: Exception) {
            log.w("opening the map window on display ${d.id} failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Automatic returns in the last minute ([onGmPage]), so GM and the app can never ping-pong. */
    private val autoReturns = ArrayDeque<Long>()
    private const val AUTO_RETURNS_PER_MINUTE = 3

    /**
     * GM's driver display reported a page change ([GmClusterPageService]). Selecting the full-map
     * page again brings CarPlay's map back, the return openautolink built for the same problem:
     * GM covers our window when the driver leaves the map page, and we close it then, so a map
     * page selected while no window is open means the driver came back for it. Never more than
     * [AUTO_RETURNS_PER_MINUTE] a minute; never when the page left is reported.
     */
    fun onGmPage(ctx: Context, selected: Int?, type: String?) {
        if (selected != 1 || !enabled || display == null || size == null) return
        if (DriverDisplayActivity.isOpen()) return
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(autoReturns) {
            while (autoReturns.isNotEmpty() && now - autoReturns.first() > 60_000L) autoReturns.removeFirst()
            if (autoReturns.size >= AUTO_RETURNS_PER_MINUTE) {
                log.w("map page selected (${type ?: "?"}) — not returning: $AUTO_RETURNS_PER_MINUTE returns in the last minute")
                return
            }
            autoReturns.addLast(now)
        }
        launches = 0
        show(ctx, "GM's map page selected (${type ?: "?"})")
    }

    /** The first map frame of the session reached the driver display. */
    fun onFirstFrame() {
        if (firstFrameLogged) return
        firstFrameLogged = true
        log.i("first map frame on the driver display")
    }

    /** Session over: close the window, which also closes its decoder. Any thread. */
    fun close(why: String) {
        size = null
        display = null
        DriverDisplayActivity.closeOpen(why)
    }

    private fun stateName(s: Int): String = when (s) {
        Display.STATE_ON -> "on"
        Display.STATE_OFF -> "off"
        Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND -> "doze"
        else -> s.toString()
    }
}
