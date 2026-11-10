package wasidremin.gmccpa.nav

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import wasidremin.gmccpa.ProbeLog
import wasidremin.gmccpa.ocbm.PixelRect
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

    private const val KEY_MARGINS = "directions_margins"
    private const val KEY_TURN_CARD = "turn_card"
    /** How long the directions-area outline stays on the driver display after a change. */
    const val PREVIEW_MS = 30_000L

    /**
     * The directions area (openautolink's idea, our code): margins, in percent of the driver display,
     * that CarPlay keeps its turn card, arrival time and other non-map content out of, so GM's gauges
     * drawn over the map do not cover them. The map itself still fills the display. Declared as the
     * cluster screen's safe area at subscribe ([safeArea]), so a change applies at the next connect.
     */
    data class Margins(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0) {
        val isZero: Boolean get() = left == 0 && top == 0 && right == 0 && bottom == 0
        fun clamped() = Margins(left.coerceIn(0, MAX_EDGE), top.coerceIn(0, MAX_EDGE),
            right.coerceIn(0, MAX_EDGE), bottom.coerceIn(0, MAX_EDGE))
        override fun toString() = "left $left%, top $top%, right $right%, bottom $bottom%"
    }
    /** No edge may take more than this percent, so two opposite edges always leave 30% or more. */
    const val MAX_EDGE = 35

    /**
     * openautolink's default for GM's 2000x810 driver display (directions inside 558,70–1694,668 on a
     * 2024 Blazer EV, `docs/ops/04_OPEN_ITEMS.md`), as whole percents: 560,72–1700,674 here.
     */
    val GM_LAYOUT = Margins(left = 28, top = 9, right = 15, bottom = 17)

    fun margins(ctx: Context): Margins {
        val raw = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MARGINS, null)
            ?: return Margins()
        val n = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
        return if (n.size == 4) Margins(n[0], n[1], n[2], n[3]).clamped() else Margins()
    }

    fun setMargins(ctx: Context, m: Margins) {
        val c = m.clamped()
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MARGINS, "${c.left},${c.top},${c.right},${c.bottom}").apply()
        previewUntil = android.os.SystemClock.elapsedRealtime() + PREVIEW_MS
        DriverDisplayActivity.refreshPreview()
    }

    /** Settings: draw [TurnCard] over CarPlay's map on the driver display during a route. On by default. */
    @Volatile var turnCard = true
        private set

    fun setTurnCard(ctx: Context, on: Boolean) {
        turnCard = on
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_TURN_CARD, on).apply()
        log.i("turn card on the driver display ${if (on) "ON" else "off"} (Settings)")
        // Show where it sits, as a margin change does.
        if (on) previewUntil = android.os.SystemClock.elapsedRealtime() + PREVIEW_MS
        DriverDisplayActivity.refreshPreview()
    }

    /** Main thread reads it; the window outlines the directions area until then ([setMargins]). */
    @Volatile var previewUntil = 0L
        private set

    /** Settings changed what the window draws: outline it and show sample cards for [PREVIEW_MS]. */
    fun preview() {
        previewUntil = SystemClock.elapsedRealtime() + PREVIEW_MS
        DriverDisplayActivity.refreshPreview()
    }

    /** The directions area to declare for [size], or null for the whole display. */
    fun safeArea(ctx: Context, size: PixelSize): PixelRect? {
        val m = margins(ctx)
        val a = safeArea(size, m)
        if (a != null) log.i("directions area on the driver display: ${a.width}x${a.height} at ${a.x},${a.y} ($m)")
        return a
    }

    /**
     * Pure: [m] applied to [size], each edge rounded down to even pixels as every coded size is.
     * Null when no margin is set, so the declaration stays the whole display.
     */
    fun safeArea(size: PixelSize, m: Margins): PixelRect? {
        val c = m.clamped()
        if (c.isZero) return null
        fun px(total: Int, pct: Int) = (total * pct / 100) and 1.inv()
        val l = px(size.width, c.left); val r = px(size.width, c.right)
        val t = px(size.height, c.top); val b = px(size.height, c.bottom)
        val w = (size.width - l - r) and 1.inv()
        val h = (size.height - t - b) and 1.inv()
        if (w <= 0 || h <= 0) return null
        return PixelRect(l, t, w, h)
    }

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

    /**
     * GM has the driver display this session: it hid the map window (another page, its own map), or
     * there is no driver display. Turn-by-turn in the cluster stands down only while CarPlay's map
     * holds the display ([holdsDisplay]); on 2026-11-09 it stood down for the whole drive because it
     * keyed on the setting, so switching pages left the cluster with no turns at all.
     */
    @Volatile private var yielded = false
    /** On, and CarPlay's map holds the driver display (or is about to): the cluster turns stand down. */
    val holdsDisplay: Boolean get() = enabled && !yielded

    private fun setYielded(y: Boolean, why: String) {
        if (yielded == y) return
        yielded = y
        ClusterNav.onDriverDisplayChanged(why)
    }

    /** Main thread. The window was hidden: GM has the driver display until the map is opened again. */
    fun onHidden() {
        val left = mapWantedUntil - SystemClock.elapsedRealtime()
        if (left > 0) {
            log.i("map window covered after the long-press — the cluster turns stay off up to ${left / 1000}s so GM's map page can be picked")
            return
        }
        setYielded(true, "GM took the driver display back")
    }

    /**
     * After the long-press ([reopen]), a covered window does not bring the turns back until this
     * time. GM offers its map page only while no app is navigating (2026-11-09 evening: it came
     * back three times, each after the turns had ended, and never while they ran), and it covers the
     * window ~1 s after the long-press because the driver is on another page. Before this the turns
     * restarted 0.2 s later and the map page never appeared.
     */
    @Volatile private var mapWantedUntil = 0L
    private const val MAP_WANTED_MS = 20_000L
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val mapWantedExpired = Runnable {
        if (mapWantedUntil == 0L || SystemClock.elapsedRealtime() < mapWantedUntil) return@Runnable
        mapWantedUntil = 0L
        if (!DriverDisplayActivity.isOpen()) setYielded(true, "GM's map page was not picked within ${MAP_WANTED_MS / 1000}s of the long-press")
    }

    fun load(ctx: Context) {
        if (loaded) return
        enabled = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
        turnCard = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TURN_CARD, true)
        ClusterLayout.load(ctx)
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
        setYielded(declared == null, if (declared == null) "no driver display this session" else "the map is declared on the driver display")
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
        offMapPage = false
        mapWantedUntil = now + MAP_WANTED_MS
        main.removeCallbacks(mapWantedExpired)
        main.postDelayed(mapWantedExpired, MAP_WANTED_MS)
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
        val wasYielded = yielded
        try {
            val am = from.getSystemService(ActivityManager::class.java)
            if (am?.isActivityStartAllowedOnDisplay(from, d.id, intent) != true) {
                log.w("window on display ${d.id} refused now ($why)")
                return
            }
            // The cluster turns end before the window opens: GM takes the driver display for its own
            // map while an app is navigating (2026-11-08 16:14:33, 0.6 s after navigationStarted).
            setYielded(false, "opening the map on the driver display")
            from.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(d.id).toBundle())
            log.i("opening the map window on display ${d.id} ($why, launch $launches of $LAUNCHES_PER_SESSION)")
        } catch (e: Exception) {
            log.w("opening the map window on display ${d.id} failed: ${e.javaClass.simpleName}: ${e.message}")
            setYielded(wasYielded, "the map window did not open")
        }
    }

    /** Automatic returns in the last minute ([onGmPage]), so GM and the app can never ping-pong. */
    private val autoReturns = ArrayDeque<Long>()
    private const val AUTO_RETURNS_PER_MINUTE = 3
    /**
     * The page events that mean "the map page": not `ZONE3_SELECTED`, GM's side zone, whose
     * selection reopened the map on 2026-11-09 12:12:04 and had it hidden again 1.2 s later.
     */
    internal val RETURN_ACTIONS = setOf("gm.cluster.action.FULL_MAP_SELECTED", "gm.cluster.action.APP_SELECTED")
    private const val FULL_MAP = "gm.cluster.action.FULL_MAP_SELECTED"

    /**
     * GM reported the driver leaving the map page (`FULL_MAP_SELECTED state=0`) and not coming back
     * yet. While set, CarPlay coming to the front does not reopen the map: on 2026-11-09 13:40:30 the
     * cluster turns' own bootstrap brought CarPlay back to the front, that reopened the map over
     * GM's other page, the turns stood down for it, and GM hid it again 1.2 s later. Only the map
     * page selected again ([onGmPage]) or the long-press ([reopen]) brings it back then.
     */
    @Volatile private var offMapPage = false

    /** Main thread. CarPlay's screen came to the front: reopen the map unless the driver left its page. */
    fun showOnResume(from: Context) {
        if (offMapPage) return
        show(from, "CarPlay screen in front")
    }

    /**
     * GM's driver display reported a page change ([GmClusterPageService]). Selecting the full-map
     * page again brings CarPlay's map back, the return openautolink built for the same problem:
     * GM covers our window when the driver leaves the map page, and we close it then, so a map
     * page selected while no window is open means the driver came back for it. Never more than
     * [AUTO_RETURNS_PER_MINUTE] a minute; never when the page left is reported.
     */
    fun onGmPage(ctx: Context, action: String?, selected: Int?, type: String?) {
        if (action == FULL_MAP && selected == 0) {
            // Leaving the map page is the driver's answer: the turns come back with the next cover.
            offMapPage = true
            mapWantedUntil = 0L
        }
        if (action !in RETURN_ACTIONS) return
        if (selected == 1) offMapPage = false
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
