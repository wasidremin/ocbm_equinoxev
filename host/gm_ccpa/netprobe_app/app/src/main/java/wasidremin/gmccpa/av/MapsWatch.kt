package wasidremin.gmccpa.av

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import wasidremin.gmccpa.ProbeLog
import wasidremin.gmccpa.match.ScreenModel
import wasidremin.gmccpa.nav.ClusterNav

/**
 * Tells the cluster when full-screen Apple Maps is on the centre screen, so it can show the album
 * art while Maps already shows the turns (docs/ops/08_FUTURE_TASKS.md T13, owner's idea of
 * 2026-11-02). The Dashboard counts as not Maps: it shows a turn card of its own.
 *
 * No CarPlay message says which app is in front, so this reads the screen: every [PERIOD_MS], while
 * a route is up and the cluster has turns to show, one frame is grabbed and searched for Maps' turn
 * card ([ScreenModel.mapsCard], well under a millisecond on a 1/4-scale frame). The cluster switches
 * only after [AGREE] reads in a row, so a quick app switch does not make it flicker. A top-bar launch
 * from this app switches at once ([hint]).
 */
object MapsWatch {
    private val log = ProbeLog.sub("mapswatch")

    const val PERIOD_MS = 2_500L
    const val AGREE = 2

    private var exec: ScheduledExecutorService? = null
    private val agree = MapsAgreement(AGREE)
    /** Reused on the single watch thread; a frame is ~10 MB of ARGB. */
    private var argbBuf: IntArray? = null
    /** The first read of each route is logged, so a drive without Maps in front still shows the watch ran. */
    private var announced = false

    /** Idempotent. Ticks are cheap while there is nothing to watch. */
    @Synchronized fun start(ctx: Context) {
        if (exec != null) return
        val app = ctx.applicationContext
        exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cp-mapswatch").apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay({ runCatching { tick(app) } }, PERIOD_MS, PERIOD_MS, TimeUnit.MILLISECONDS) }
    }

    /** A top-bar launch that reached [role]: what is in front is known without reading the screen. */
    fun hint(role: String) {
        exec?.execute { apply(agree.force(role == "maps"), "top-bar launch of $role") }
    }

    private fun tick(ctx: Context) {
        val watching = ClusterNav.enabled && ClusterNav.artWhileMaps && ClusterNav.bound &&
            ClusterNav.route.snapshot.routeActive
        if (!watching) {
            announced = false
            apply(agree.force(false), "no route in the cluster")
            return
        }
        // The launcher reads the same borrowed frame buffer; one skipped read changes nothing.
        if (VisionLauncher.busy) return
        val sv = CarPlayActivity.liveSurfaceView()
        if (sv == null) {
            apply(agree.force(false), "CarPlay is not on screen")
            return
        }
        val bmp = when (val res = CarPlayFrameGrabber.captureBlocking(sv)) {
            is CarPlayFrameGrabber.CaptureResult.Success -> res.bitmap
            is CarPlayFrameGrabber.CaptureResult.Failure -> return
        }
        val w = bmp.width
        val h = bmp.height
        val buf = argbBuf?.takeIf { it.size == w * h } ?: IntArray(w * h).also { argbBuf = it }
        bmp.getPixels(buf, 0, w, 0, 0, w, h)
        val g = ScreenModel.downscale(buf, w, h)
        val ui = VisionLauncher.knownUi(ctx)
        val ev = ScreenModel.mapsCard(g, ScreenModel.geometry(g, ui))
        if (!announced) {
            announced = true
            log.i("watching for full-screen Maps every ${PERIOD_MS} ms at ui=%.3f; first read: %s %s"
                .format(java.util.Locale.US, ui, if (ev.present) "turn card" else "no turn card", ev))
        }
        apply(agree.offer(ev.present), "turn card ${if (ev.present) "found" else "not found"}: $ev")
    }

    private fun apply(changed: Boolean, why: String) {
        if (changed) ClusterNav.setMapsInFront(agree.state, why)
    }
}

/**
 * Debounce for [MapsWatch]: the state follows a read only after [need] reads in a row agree.
 * [offer] and [force] return true when the state changed.
 */
internal class MapsAgreement(private val need: Int) {
    var state = false
        private set
    private var last: Boolean? = null
    private var run = 0

    fun offer(read: Boolean): Boolean {
        run = if (read == last) run + 1 else 1
        last = read
        if (read == state || run < need) return false
        state = read
        return true
    }

    fun force(value: Boolean): Boolean {
        last = value
        run = need
        if (value == state) return false
        state = value
        return true
    }
}
