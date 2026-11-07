package wasidremin.gmccpa.nav

import android.car.Car
import android.car.CarAppFocusManager
import android.content.Context
import android.content.pm.PackageManager
import wasidremin.gmccpa.ProbeLog
import java.util.concurrent.Executors

/**
 * Tells AAOS "this app is the active navigation app" while a CarPlay route is up and the cluster
 * turn-by-turn setting is on. Ported from enigy/carlink `car/AppFocusClaimer.kt` (Unlicense).
 *
 * `CarAppFocusManager` is the one piece of the car API that is permission-free AND meaningful for a
 * projection app: holding `APP_FOCUS_TYPE_NAVIGATION` makes the platform's navigation-context owner
 * point at us (`AppFocusService` -> the instrument cluster's nav-context owner) and tells the previous
 * holder, a native maps app mid-route, that it has lost the road (`onAppFocusOwnershipLost`). That is
 * the arbitration a native CarPlay integration performs when the phone starts navigating. Reporting
 * the turn-by-turn itself (`CarNavigationStatusManager`) is privileged and out of scope.
 *
 * What GM does with the claim on the Equinox is not known; the grant and any loss are logged
 * (`nav: focus …`). Tied to the cluster setting so turning that off also takes this out of play.
 *
 * Edge-driven from [ClusterNav.onRecord]; the car service is reached on its own thread, since
 * `Car.createCar(ctx)` blocks until it connects.
 */
object NavFocus {
    private val log = ProbeLog.sub("nav")
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "cp-navfocus").apply { isDaemon = true } }

    private var car: Car? = null
    private var mgr: CarAppFocusManager? = null
    private var started = false
    /** What the route wants; applied once the car service is up. */
    @Volatile private var want = false
    @Volatile var holds = false
        private set

    private val ownership = object : CarAppFocusManager.OnAppFocusOwnershipCallback {
        override fun onAppFocusOwnershipLost(appType: Int) {
            if (appType != CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION) return
            holds = false
            log.i("nav: focus — NAVIGATION focus lost to another app")
        }

        override fun onAppFocusOwnershipGranted(appType: Int) {
            if (appType != CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION) return
            holds = true
            log.i("nav: focus — NAVIGATION focus granted")
        }
    }

    /** Idempotent; a no-op off an automotive build. Any thread. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        exec.execute {
            if (started) return@execute
            started = true
            if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return@execute
            runCatching {
                val c = Car.createCar(app) ?: throw IllegalStateException("Car.createCar returned null")
                car = c
                mgr = c.getCarManager(Car.APP_FOCUS_SERVICE) as? CarAppFocusManager
                if (mgr == null) log.w("nav: focus — CarAppFocusManager unavailable")
                apply()
            }.onFailure { log.w("nav: focus — car service unavailable: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    /** True while a CarPlay route is up (and the cluster setting is on). Safe to repeat; any thread. */
    fun setNavigating(active: Boolean) {
        if (want == active) return
        want = active
        exec.execute { apply() }
    }

    /** On [exec]. */
    private fun apply() {
        val m = mgr ?: return
        runCatching {
            if (want && !holds) {
                val r = m.requestAppFocus(CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION, ownership)
                holds = r == CarAppFocusManager.APP_FOCUS_REQUEST_SUCCEEDED
                log.i("nav: focus — requestAppFocus(NAVIGATION) -> ${if (holds) "granted" else "refused ($r)"}")
            } else if (!want && holds) {
                m.abandonAppFocus(ownership, CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION)
                holds = false
                log.i("nav: focus — abandoned NAVIGATION focus")
            }
        }.onFailure { log.w("nav: focus — app-focus call failed: ${it.javaClass.simpleName}: ${it.message}") }
    }
}
