package wasidremin.gmccpa.nav

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import wasidremin.gmccpa.ProbeLog
import java.util.concurrent.atomic.AtomicReference

/**
 * Relays the CarPlay route to the cluster through `NavigationManager`.
 *
 * Ported from lvalen91/carlink `cluster/ClusterMainSession.kt` (public domain), the GM path:
 * [RelayScreen] is never what the driver reads — on GM the cluster is fed from the Trip data — and
 * the first session the host creates owns the NavigationManager while any second one (carlink saw a
 * MAIN + CLUSTER pair on the emulator) stays passive.
 *
 * Three deliberate departures from carlink:
 * 1. **Start and end follow iAP2's own RouteGuidanceState**, not inference. carlink called
 *    `navigationStarted()` the moment the screen was created and ended a trip 10 s after any terminal
 *    maneuver TYPE; but with iAP2 the "arrive" maneuver is current for the whole final stretch, so
 *    that timer ends navigation miles early and restarts it on the next record. Here a trip starts on
 *    the first record with a turn to show, ends on state 0, and [ARRIVED_END_MS] after state 2.
 * 2. **The host's onStopNavigation is respected** until the route ends. It means another navigation
 *    app took over (the car's own Maps, say); restarting on the next record would fight it.
 * 3. **Updates are coalesced** to one per [COALESCE_MS], keeping carlink's 200 ms, but never dropped:
 *    the last snapshot always lands.
 *
 * NavigationManager is main-thread only (it checks); snapshots arrive on the seam thread.
 */
internal class ClusterNavSession : Session(), ClusterNav.Sink {
    private val log = ProbeLog.sub("nav")
    private val main = Handler(Looper.getMainLooper())

    private var nav: NavigationManager? = null
    private var navigating = false
    /** The host stopped us; stay out until this route ends. */
    private var stoppedByHost = false
    /** This route reached state 2 and its arrival grace ran out. */
    private var arrivalEnded = false
    /** Armed ONCE per arrival: re-posting on every ~1 Hz record would push the deadline forever. */
    private var arrivalArmed = false
    private var updates = 0L

    private val pending = AtomicReference<RouteGuidance.Snapshot?>(null)
    private val apply = Runnable { pending.getAndSet(null)?.let { render(it) } }
    private val arrivalEnd = Runnable {
        arrivalArmed = false
        arrivalEnded = true
        if (navigating) end("arrived ${ARRIVED_END_MS / 1000}s ago and the phone has not ended the route")
    }

    private fun disarmArrival() {
        main.removeCallbacks(arrivalEnd)
        arrivalArmed = false
    }

    override fun onCreateScreen(intent: Intent): Screen {
        ClusterNav.load(carContext)
        if (!ClusterNav.attach(this)) return RelayScreen(carContext)
        val nm = runCatching { carContext.getCarService(NavigationManager::class.java) }
            .onFailure { log.e("nav: no NavigationManager: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()
        nav = nm
        // Must be set BEFORE navigationStarted(), which throws without one.
        nm?.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() {
                log.w("nav: host stopped navigation (another navigation app took over?) — staying out until this route ends")
                navigating = false
                stoppedByHost = true
                disarmArrival()
            }
        })
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                main.removeCallbacks(apply)
                disarmArrival()
                if (navigating) end("session destroyed")
                nav = null
                log.i("nav: session destroyed after $updates trip updates")
                ClusterNav.detach(this@ClusterNavSession)
            }
        })
        // The route may already be up: the binding is made BECAUSE one started.
        onRoute(ClusterNav.route.snapshot)
        return RelayScreen(carContext)
    }

    /** Seam thread. Keep the newest; one render per [COALESCE_MS]. */
    override fun onRoute(s: RouteGuidance.Snapshot) {
        if (pending.getAndSet(s) == null) main.postDelayed(apply, COALESCE_MS)
    }

    private fun render(s: RouteGuidance.Snapshot) {
        val nm = nav ?: return
        // Turned off in Settings while still bound: disabling the component normally tears the
        // binding down, but this must not depend on it.
        if (!ClusterNav.enabled) {
            disarmArrival()
            if (navigating) end("turned off in Settings")
            return
        }
        if (!s.routeActive) {
            disarmArrival()
            stoppedByHost = false
            arrivalEnded = false
            if (navigating) end("route ended (state ${s.state ?: "-"})")
            return
        }
        if (s.state != RouteGuidance.ARRIVED) {
            // Off "arrived" (a multi-stop route moving on to its next leg): the next arrival gets
            // its own grace.
            disarmArrival()
            arrivalEnded = false
        }
        // Loading / rerouting with no turn yet: leave the cluster on what it last showed.
        if (stoppedByHost || arrivalEnded || !s.showable) return
        try {
            if (!navigating) {
                nm.navigationStarted()
                navigating = true
                log.i("nav: navigationStarted (state ${s.state} ${ClusterNav.stateName(s.state)})")
            }
            nm.updateTrip(TripFactory.build(s, carContext))
            updates++
            if (updates == 1L || updates % LOG_EVERY == 0L) {
                log.i("nav: updateTrip #$updates turn #${s.current?.index} cpType=${s.current?.type ?: "?"} " +
                    "-> TYPE ${ManeuverMap.type(s.current?.type, s.current?.drivingSide)} " +
                    "dist=${s.distanceToManeuverText ?: "?"}u${s.distanceToManeuverUnits ?: "?"}")
            }
        } catch (t: Throwable) {
            // IllegalStateException here is the library refusing (not started / no callback); a
            // RemoteException-wrapped failure is the host. Either way one line, and the next record retries.
            log.e("nav: trip update failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        if (s.state == RouteGuidance.ARRIVED && navigating && !arrivalArmed) {
            arrivalArmed = true
            main.postDelayed(arrivalEnd, ARRIVED_END_MS)
        }
    }

    private fun end(why: String) {
        runCatching { nav?.navigationEnded() }
            .onFailure { log.e("nav: navigationEnded failed: ${it.javaClass.simpleName}: ${it.message}") }
        navigating = false
        log.i("nav: navigationEnded — $why ($updates trip updates so far)")
    }

    /**
     * Shown only for the ~1 s the hidden CarAppActivity is in front during [ClusterNav.bootstrap]
     * (and on a non-GM host that renders the session itself, where it is honest about what this is).
     */
    private class RelayScreen(ctx: CarContext) : Screen(ctx) {
        override fun onGetTemplate(): Template =
            NavigationTemplate.Builder()
                .setNavigationInfo(
                    MessageInfo.Builder("GM CCPA — instrument cluster")
                        .setText("Connecting CarPlay directions to the cluster. CarPlay returns in a moment.")
                        .build(),
                )
                .setActionStrip(ActionStrip.Builder().addAction(Action.APP_ICON).build())
                .build()
    }

    private companion object {
        const val COALESCE_MS = 200L
        /** Apple Maps usually ends the route itself soon after arrival; this is the backstop. */
        const val ARRIVED_END_MS = 10_000L
        /** ~1 Hz updates: a line on the first and then every minute or so. */
        const val LOG_EVERY = 60L
    }
}
