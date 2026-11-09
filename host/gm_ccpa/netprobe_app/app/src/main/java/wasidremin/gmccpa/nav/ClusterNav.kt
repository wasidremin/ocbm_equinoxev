package wasidremin.gmccpa.nav

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import wasidremin.gmccpa.ProbeLog
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

/**
 * CarPlay turn-by-turn in the instrument cluster: the half that lives in `src/main`.
 *
 * ## The chain, and why it is split across two source sets
 * A third-party app reaches the GM cluster only through the Car App Library: a navigation
 * CarAppService calls `NavigationManager.updateTrip()`, and the Templates Host — which holds the
 * privileged `CAR_NAVIGATION_MANAGER` this app cannot — forwards the trip to CarService, GM's
 * VMSPlugin and the cluster. lvalen91/carlink (public domain) proved that chain on a Silverado; its
 * session, trip builder and maneuver table are ported in `src/carapp`.
 *
 * `src/carapp` is compiled by the Gradle builds only. `tools/build_apk.sh` builds `src/main` with
 * aapt2 and no AndroidX, so everything here talks to that code through [Sink] and by class NAME, and
 * reports [Support.NOT_IN_BUILD] when the classes are absent.
 *
 * ## The flash
 * The Templates Host binds a CarAppService only after the app launches
 * `androidx.car.app.activity.CarAppActivity`. [bootstrap] launches it (hidden: own task, translucent,
 * out of recents) and moves the previous screen back in front a second later, which is visible as a
 * brief flash. It runs only when it has something to show — a route is up and no session is bound —
 * and only while one of this app's own screens is in front, so it never covers another app.
 *
 * Off by default (Settings ▸ Navigation): the chain is not yet verified on a VCU car.
 */
object ClusterNav {
    private val log = ProbeLog.sub("nav")
    private val main = Handler(Looper.getMainLooper())

    const val SERVICE_CLASS = "wasidremin.gmccpa.nav.ClusterNavService"
    const val CAR_APP_ACTIVITY = "androidx.car.app.activity.CarAppActivity"
    private const val TEMPLATES_HOST_FEATURE = "android.software.car.templates_host"

    private const val PREFS = "cluster_nav"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ART_WHILE_MAPS = "art_while_maps"

    /** carlink's figure for how long the CarAppActivity must stay in front for the host handshake. */
    private const val BRING_BACK_MS = 1_000L
    /** No session this long after a bootstrap means the host did not bind. Logged, never retried in a loop. */
    private const val BIND_DEADLINE_MS = 10_000L
    /** Minimum gap between two bootstraps, so a host that never binds cannot make the screen strobe. */
    private const val BOOTSTRAP_COOLDOWN_MS = 120_000L
    /** Rebinds after a session the car ended mid-route: at most this many in [REBIND_WINDOW_MS]. */
    private const val REBIND_LIMIT = 3
    private const val REBIND_WINDOW_MS = 10 * 60_000L
    private const val REBIND_DELAY_MS = 2_000L

    /** The merged route. Fed from the seam by [onRecord]; read by the session. */
    val route = RouteGuidance()

    /** The Car App session's half: receives every changed snapshot. Called on the seam thread. */
    interface Sink {
        fun onRoute(s: RouteGuidance.Snapshot)
    }

    enum class Support { OK, NOT_IN_BUILD, OLD_ANDROID, NO_TEMPLATES_HOST }

    @Volatile var enabled: Boolean = false
        private set

    /**
     * On in Settings and not standing down while CarPlay's map holds the driver display
     * ([DriverDisplay.holdsDisplay]). It is one or the other at any moment: GM takes the driver display
     * back for its own map as soon as an app starts navigation (2026-11-08 16:14:33.502
     * `navigationStarted`, focus lost to GM 6 ms later, the map window hidden at 34.102), which is
     * what openautolink found on the Blazer too. Once GM has the display (another page), the turns
     * come back to the cluster.
     */
    val active: Boolean get() = enabled && !DriverDisplay.holdsDisplay
    /** Settings ▸ Navigation: give the cluster to the album art while full-screen Maps shows the turns. */
    @Volatile var artWhileMaps: Boolean = true
        private set
    /** Full-screen Apple Maps is on the centre screen ([wasidremin.gmccpa.av.MapsWatch]): the session stands down. */
    @Volatile var mapsInFront: Boolean = false
        private set
    @Volatile private var loaded = false

    private val sink = AtomicReference<Sink?>(null)
    /** True while a session holds the Templates Host binding. */
    val bound: Boolean get() = sink.get() != null

    /** The app's own Activity that is resumed right now, if any. */
    @Volatile private var front: WeakReference<Activity>? = null
    /** A route wanted a binding while none of our screens was in front; run it when one resumes. */
    @Volatile private var bootstrapPending = false
    private var lastBootstrapAt = 0L
    /** One bootstrap per route: a host that refuses must not be asked again on every record. */
    @Volatile private var triedThisRoute = false

    /** Idempotent. Call from every entry point that can be the first in the process. */
    fun load(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val app = ctx.applicationContext
            enabled = prefs(app).getBoolean(KEY_ENABLED, false)
            artWhileMaps = prefs(app).getBoolean(KEY_ART_WHILE_MAPS, true)
            (app as? Application)?.registerActivityLifecycleCallbacks(FrontTracker)
            DriverDisplay.load(app)
            loaded = true
        }
        NavFocus.start(ctx)
        applyComponent(ctx.applicationContext)
        log.i("nav: cluster turn-by-turn ${if (enabled) "ON" else "off"} support=${support(ctx)}")
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        load(ctx)
        enabled = on
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
        applyComponent(ctx.applicationContext)
        log.i("nav: cluster turn-by-turn ${if (on) "ON" else "off"} (Settings)${if (on && !active) " — standing down while the driver display shows CarPlay's map" else ""}")
        NavFocus.setNavigating(active && route.snapshot.routeActive)
        // A bound session re-renders against the new switch: off ends its trip now.
        sink.get()?.onRoute(route.snapshot)
        if (active) {
            triedThisRoute = false
            lastBootstrapAt = 0L
            if (route.snapshot.showable) requestBootstrap("enabled with a route up")
        }
    }

    /**
     * The driver display's map was turned on or off, opened, or hidden by GM: stand down, or carry on
     * with the route. A bootstrap here goes through the same limit as a rebind, so a driver flicking
     * pages cannot keep flashing the hidden activity.
     */
    fun onDriverDisplayChanged(why: String = "driver display map ${if (DriverDisplay.enabled) "ON" else "off"}") {
        if (!enabled) return
        log.i("nav: cluster turn-by-turn ${if (active) "back on" else "standing down"} — $why (bound=$bound)")
        NavFocus.setNavigating(active && route.snapshot.routeActive)
        sink.get()?.onRoute(route.snapshot)
        if (active && sink.get() == null && route.snapshot.showable && takeRebind("the driver display map gave way")) {
            triedThisRoute = false
            lastBootstrapAt = 0L
            requestBootstrap(why)
        }
    }

    fun setArtWhileMaps(ctx: Context, on: Boolean) {
        load(ctx)
        artWhileMaps = on
        prefs(ctx).edit().putBoolean(KEY_ART_WHILE_MAPS, on).apply()
        log.i("nav: album art while Maps is in front ${if (on) "ON" else "off"} (Settings)")
        if (!on) setMapsInFront(false, "turned off in Settings")
    }

    /**
     * Any thread. While [on], a bound session ends navigation so the cluster shows the album art
     * (the cluster clears at once when navigation ends, owner-confirmed 2026-11-02); when it goes
     * back off, the next render starts navigation and sends the trip again.
     */
    fun setMapsInFront(on: Boolean, why: String) {
        if (mapsInFront == on) return
        mapsInFront = on
        val ios = lastVisibleInApp?.let { "; iOS visible in app=$it" } ?: ""
        log.i(if (on) "nav: full-screen Apple Maps is in front — the cluster shows the album art ($why$ios)"
            else "nav: Apple Maps is not in front — turns back in the cluster ($why$ios)")
        sink.get()?.onRoute(route.snapshot)
    }

    fun support(ctx: Context): Support {
        val pm = ctx.packageManager
        val declared = runCatching {
            pm.getActivityInfo(ComponentName(ctx, CAR_APP_ACTIVITY), PackageManager.MATCH_DISABLED_COMPONENTS)
            pm.getServiceInfo(ComponentName(ctx, SERVICE_CLASS), PackageManager.MATCH_DISABLED_COMPONENTS)
        }.isSuccess
        return when {
            !declared -> Support.NOT_IN_BUILD
            Build.VERSION.SDK_INT < 29 -> Support.OLD_ANDROID
            !pm.hasSystemFeature(TEMPLATES_HOST_FEATURE) -> Support.NO_TEMPLATES_HOST
            else -> Support.OK
        }
    }

    /** One line for Settings. */
    fun statusText(ctx: Context): String = when (support(ctx)) {
        Support.NOT_IN_BUILD -> "Not in this build. The Play build has it."
        Support.OLD_ANDROID -> "Needs Android 10 or later."
        Support.NO_TEMPLATES_HOST -> "This car has no Templates Host, so the cluster cannot take turns from apps."
        Support.OK -> when {
            !enabled -> "Shows CarPlay's next turn, distance and arrival time in the instrument cluster."
            !active -> "Paused while CarPlay map on the driver display is on: GM takes the driver display back when navigation starts."
            bound -> "Connected. CarPlay turns show in the cluster while a route is active."
            else -> "Connects when CarPlay starts a route. The screen flickers once while it does."
        }
    }

    /**
     * The service ships disabled (src/carapp manifest) so the host never sees it while the feature is
     * off. DONT_KILL_APP: this runs inside a live CarPlay session.
     */
    private fun applyComponent(app: Context) {
        if (support(app) == Support.NOT_IN_BUILD) return
        val want = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val cn = ComponentName(app, SERVICE_CLASS)
        runCatching {
            if (app.packageManager.getComponentEnabledSetting(cn) != want)
                app.packageManager.setComponentEnabledSetting(cn, want, PackageManager.DONT_KILL_APP)
        }.onFailure { log.e("nav: could not ${if (enabled) "enable" else "disable"} $SERVICE_CLASS: ${it.message}") }
    }

    // ---- seam side ------------------------------------------------------------------------------

    /** A `routeGuidance` or `maneuver` record. Runs on the `cp-meta` thread: cheap work only. */
    fun onRecord(kind: String, json: JSONObject) {
        logVisibleInApp(json)
        val before = route.snapshot
        val s = route.onRecord(kind, json) ?: return
        logTransition(before, s)
        NavFocus.setNavigating(active && s.routeActive)
        if (!s.routeActive) {
            triedThisRoute = false
            bootstrapPending = false
        }
        sink.get()?.onRoute(s)
        if (active && s.showable && sink.get() == null && !triedThisRoute) requestBootstrap("route active, no session bound")
    }

    /** The CarPlay session ended. Clears the route and tells a bound session to end navigation. */
    fun onSessionEnded(why: String) {
        val had = route.snapshot.routeActive
        val s = route.clear()
        triedThisRoute = false
        bootstrapPending = false
        NavFocus.setNavigating(false)
        if (had) log.i("nav: route cleared — $why")
        sink.get()?.onRoute(s)
    }

    /**
     * State, index and type changes only. No road or destination names: the capture is uploadable,
     * and where someone drove is more sensitive than what they listened to.
     */
    private fun logTransition(a: RouteGuidance.Snapshot, b: RouteGuidance.Snapshot) {
        if (a.state != b.state) log.i("nav: route state ${a.state ?: "-"} -> ${b.state} (${stateName(b.state)})")
        val ca = a.current
        val cb = b.current
        if (cb != null && (ca?.index != cb.index || ca.type != cb.type)) {
            log.i("nav: turn #${cb.index}/${b.maneuverCount ?: "?"} type=${cb.type ?: "?"} side=${cb.drivingSide ?: "?"}" +
                " next=${b.next?.type ?: "-"} bound=${bound} enabled=$enabled")
        }
    }

    /** Last RouteGuidanceVisibleInApp seen; written on `cp-meta`, also logged by [setMapsInFront]. */
    @Volatile private var lastVisibleInApp: Int? = null

    /**
     * RouteGuidanceVisibleInApp, forwarded by adapter firmware built after 2026-11-04. Logged next to
     * what the screen reading says, so one drive shows whether iOS sends it and whether it could
     * replace [wasidremin.gmccpa.av.MapsWatch].
     */
    private fun logVisibleInApp(json: JSONObject) {
        if (!json.has("visibleInApp")) return
        val v = json.optInt("visibleInApp")
        if (v == lastVisibleInApp) return
        lastVisibleInApp = v
        log.i("nav: iOS says route guidance visible in app=$v (screen reading: Maps in front=$mapsInFront)")
    }

    internal fun stateName(s: Int?): String = when (s) {
        RouteGuidance.NO_ROUTE -> "no route"
        RouteGuidance.ROUTE_SET -> "route set"
        RouteGuidance.ARRIVED -> "arrived"
        RouteGuidance.LOADING -> "loading"
        RouteGuidance.LOCATING -> "locating"
        RouteGuidance.REROUTING -> "rerouting"
        RouteGuidance.PROCEED_TO_ROUTE -> "proceed to route"
        else -> "unknown"
    }

    // ---- session side (src/carapp) --------------------------------------------------------------

    /** The first live session claims the binding; any other session the host creates stays passive. */
    fun attach(s: Sink): Boolean {
        val won = sink.compareAndSet(null, s)
        log.i("nav: session ${if (won) "bound — owns NavigationManager" else "extra — passive"}")
        if (won) bootstrapPending = false
        return won
    }

    fun detach(s: Sink) {
        if (sink.compareAndSet(s, null)) {
            log.i("nav: session unbound")
            rebindIfRouteUp()
        }
    }

    private val rebinds = ArrayDeque<Long>()

    /**
     * The host ended the session while a route was still up: bind again by itself (enigy/carlink
     * `cluster/ClusterRebind.kt`, whose truck lost the cluster three times in six minutes and needed a
     * button press each time). At most [REBIND_LIMIT] in [REBIND_WINDOW_MS], so a car that keeps
     * dropping the session cannot keep the screen flashing the hidden activity. Turning the setting
     * off ends the session too, but [enabled] is already false by then.
     */
    private fun rebindIfRouteUp() {
        if (!active || !route.snapshot.showable) return
        if (!takeRebind("session ended with a route up")) return
        log.i("nav: session ended with a route up — binding again in ${REBIND_DELAY_MS / 1000}s")
        main.postDelayed({
            if (!active || sink.get() != null || !route.snapshot.showable) return@postDelayed
            triedThisRoute = false
            lastBootstrapAt = 0L
            requestBootstrap("session ended with a route up")
        }, REBIND_DELAY_MS)
    }

    /** One rebind from the shared budget of [REBIND_LIMIT] in [REBIND_WINDOW_MS]; false when spent. */
    private fun takeRebind(why: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        synchronized(rebinds) {
            while (rebinds.isNotEmpty() && now - rebinds.first() > REBIND_WINDOW_MS) rebinds.removeFirst()
            if (rebinds.size >= REBIND_LIMIT) {
                log.w("nav: $why — not binding again: $REBIND_LIMIT rebinds in ${REBIND_WINDOW_MS / 60_000} min")
                return false
            }
            rebinds.addLast(now)
        }
        return true
    }

    // ---- bootstrap ------------------------------------------------------------------------------

    private fun requestBootstrap(why: String) {
        main.post {
            val act = front?.get()
            if (act == null || act.isFinishing) {
                if (!bootstrapPending) log.i("nav: bootstrap deferred until a GM CCPA screen is in front ($why)")
                bootstrapPending = true
                return@post
            }
            bootstrap(act, why)
        }
    }

    /** Main thread. Launch the hidden CarAppActivity, then put [from]'s task back in front. */
    private fun bootstrap(from: Activity, why: String) {
        bootstrapPending = false
        // Re-checked here, on the main thread: a deferred request can outlive the route that raised it.
        if (!active || sink.get() != null || triedThisRoute || !route.snapshot.showable) return
        val sup = support(from)
        if (sup != Support.OK) { log.w("nav: bootstrap skipped — support=$sup"); triedThisRoute = true; return }
        val now = SystemClock.elapsedRealtime()
        if (lastBootstrapAt != 0L && now - lastBootstrapAt < BOOTSTRAP_COOLDOWN_MS) return
        lastBootstrapAt = now
        triedThisRoute = true
        val taskId = from.taskId
        try {
            from.startActivity(Intent().setClassName(from, CAR_APP_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            log.i("nav: bootstrap — launched CarAppActivity over ${from.javaClass.simpleName} ($why)")
        } catch (t: Throwable) {
            log.e("nav: bootstrap failed to launch CarAppActivity: ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        main.postDelayed({ bringBack(from, taskId) }, BRING_BACK_MS)
        main.postDelayed({
            if (sink.get() == null) log.e("nav: EXPECTED-MISSING session — CarAppActivity launched " +
                "${BIND_DEADLINE_MS / 1000}s ago and the Templates Host bound none (look for CarApp.* lines)")
        }, BIND_DEADLINE_MS)
    }

    /** By task id, so no Activity of ours receives an onNewIntent it would act on. */
    private fun bringBack(from: Activity, taskId: Int) {
        val am = from.getSystemService(ActivityManager::class.java)
        val ok = runCatching { am.moveTaskToFront(taskId, 0) }
            .onFailure { log.w("nav: moveTaskToFront($taskId) failed: ${it.message} — reordering by intent") }
            .isSuccess
        if (!ok && !from.isDestroyed) runCatching {
            from.startActivity(Intent(from, from.javaClass)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        log.i("nav: bootstrap — returned ${from.javaClass.simpleName} to the front (bound=${bound})")
    }

    private object FrontTracker : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(a: Activity) {
            // Neither is a screen to return to: the CarAppActivity is the bootstrap itself, and the
            // USB attach trampoline is NoDisplay and finishes in the same frame.
            if (a.javaClass.name == CAR_APP_ACTIVITY || a is wasidremin.gmccpa.UsbAttachActivity) return
            // Nor is a window on another display (the driver display's map): a bootstrap launched
            // from it opens the CarAppActivity THERE, over the map, and hands back the wrong task
            // (2026-11-08 16:15:50, "launched CarAppActivity over DriverDisplayActivity").
            if ((a.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY) != android.view.Display.DEFAULT_DISPLAY) return
            front = WeakReference(a)
            if (bootstrapPending) requestBootstrap("deferred")
        }
        override fun onActivityPaused(a: Activity) {
            if (front?.get() === a) front = null
        }
        override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
        override fun onActivityStarted(a: Activity) = Unit
        override fun onActivityStopped(a: Activity) = Unit
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
        override fun onActivityDestroyed(a: Activity) = Unit
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
