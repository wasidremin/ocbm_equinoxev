package wasidremin.gmccpa.av

import android.content.Context
import android.os.SystemClock
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
 *
 * Leaving Maps takes [UNPROMPTED_AGREE] reads instead when nothing was sent to the phone since the
 * card was last seen ([noteInput], [readsToAgree]). On the 2026-11-05 drive the card was lost three
 * times for 5–15 s while Maps stayed in front: the adapter's touch counter went from #2 at 12:19:24 to
 * #3 at 12:29:11, so nothing could have changed the app. "Hey Siri" can still change it unseen, and
 * waits out the longer run.
 */
object MapsWatch {
    private val log = ProbeLog.sub("mapswatch")

    const val PERIOD_MS = 2_500L
    const val AGREE = 2
    const val UNPROMPTED_AGREE = 8
    /** A read that still shows the card this soon after a touch can be the screen before it changed. */
    const val INPUT_LEAD_MS = 3_000L

    private var exec: ScheduledExecutorService? = null
    private val agree = MapsAgreement(AGREE)
    /** Reused on the single watch thread; a frame is ~10 MB of ARGB. */
    private var argbBuf: IntArray? = null
    /** The first read of each route is logged, so a drive without Maps in front still shows the watch ran. */
    private var announced = false
    /** Uptime of the last touch, Siri press or other input sent to the phone. */
    @Volatile private var lastInputAt = 0L
    /** Uptime of the last read that found the card, or of a top-bar launch of Maps. Watch thread only. */
    private var lastCardAt = 0L

    /** Something was sent that can change the app iOS shows. Any thread. */
    fun noteInput() {
        lastInputAt = SystemClock.uptimeMillis()
    }

    /** Idempotent. Ticks are cheap while there is nothing to watch. */
    @Synchronized fun start(ctx: Context) {
        if (exec != null) return
        val app = ctx.applicationContext
        exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cp-mapswatch").apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay({ runCatching { tick(app) } }, PERIOD_MS, PERIOD_MS, TimeUnit.MILLISECONDS) }
    }

    /** A top-bar launch that reached [role]: what is in front is known without reading the screen. */
    fun hint(role: String) {
        exec?.execute {
            if (role == "maps") lastCardAt = SystemClock.uptimeMillis()
            apply(agree.force(role == "maps"), "top-bar launch of $role")
        }
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
        val geo = ScreenModel.geometry(g, ui)
        val ev = ScreenModel.mapsCard(g, geo)
        if (!announced) {
            announced = true
            log.i("watching for full-screen Maps every ${PERIOD_MS} ms at ui=%.3f; first read: %s %s"
                .format(java.util.Locale.US, ui, if (ev.present) "turn card" else "no turn card", ev))
        }
        val now = SystemClock.uptimeMillis()
        if (ev.present) lastCardAt = now
        val need = readsToAgree(agree.state, ev.present, lastInputAt, lastCardAt)
        // Each read that loses the card while Maps is believed in front, with why its edges failed.
        val why = if (agree.state && !ev.present) "turn card not found: $ev (${ScreenModel.mapsCardEdges(g, geo)})"
            else "turn card ${if (ev.present) "found" else "not found"}: $ev"
        val changed = agree.offer(ev.present, need)
        if (!changed && need == UNPROMPTED_AGREE) log.i("$why — nothing touched since the card was seen, so Maps is kept (${agree.run}/$need)")
        apply(changed, why)
    }

    private fun apply(changed: Boolean, why: String) {
        if (changed) ClusterNav.setMapsInFront(agree.state, why)
    }
}

/**
 * Reads in a row [MapsWatch] needs before following [present]. Only input sent to the phone changes
 * the app it shows, so while Maps is in front ([inFront]) a lost card counts as Maps leaving after
 * [MapsWatch.AGREE] reads only if input was sent since the card was last seen at [lastCardAt], or up
 * to [MapsWatch.INPUT_LEAD_MS] before it.
 */
internal fun readsToAgree(inFront: Boolean, present: Boolean, lastInputAt: Long, lastCardAt: Long): Int =
    if (inFront && !present && lastInputAt < lastCardAt - MapsWatch.INPUT_LEAD_MS) MapsWatch.UNPROMPTED_AGREE
    else MapsWatch.AGREE

/**
 * Debounce for [MapsWatch]: the state follows a read only after [need] reads in a row agree.
 * [offer] and [force] return true when the state changed.
 */
internal class MapsAgreement(private val need: Int) {
    var state = false
        private set
    private var last: Boolean? = null
    /** Reads in a row that agree with the last one. */
    var run = 0
        private set

    fun offer(read: Boolean, need: Int = this.need): Boolean {
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
