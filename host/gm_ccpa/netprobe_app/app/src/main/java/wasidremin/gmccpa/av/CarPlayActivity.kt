package wasidremin.gmccpa.av

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import wasidremin.gmccpa.DisplayPrefs
import wasidremin.gmccpa.ProbeLog
import wasidremin.gmccpa.ScreenMode
import wasidremin.gmccpa.StartupAnimationView
import wasidremin.gmccpa.VideoFrame
import wasidremin.gmccpa.logging.SessionTrace
import wasidremin.gmccpa.pair.NativeCore
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.math.roundToInt
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The CarPlay screen: fullscreen immersive HEVC video, AAC audio, and touch back to the iPhone.
 *
 * **Ownership:** the seam listeners and the audio player are ACTIVITY-scoped; only the video renderer
 * is SURFACE-scoped. Backgrounding therefore costs video frames and nothing else — the sockets stay
 * bound, audio keeps playing, and the producer re-dials the video seam with a ForceKeyFrame when a
 * Surface comes back. (A foreground service is still required for the session to survive this Activity
 * being destroyed; that is a separate, larger gap.)
 *
 * Owns the consumer end of the receiver's localhost seam. `forward.rs` dials OUT to `127.0.0.1:9001`
 * (video) and `:9002` (media audio) with a 2 s connect timeout, drops frames while the consumer is
 * down, and reconnects — issuing ForceKeyFrame — on the next frame. So a gap costs dropped AUs but is
 * recoverable; a failed *bind* is a permanent black screen, which is why binding is synchronous and
 * retried rather than logged and forgotten.
 *
 * The full panel is 2400x960. With the sidebar on, the adapter session advertises the panel minus
 * the rail ([VideoFrame]) and this surface is that smaller rectangle, 1:1, to the right of the rail.
 * Touch is normalised against whichever rectangle the phone was told to render into. Do not scale
 * the surface to fill the panel, and do not letterbox without revisiting [onTouch].
 */
class CarPlayActivity : Activity() {

    private val log = ProbeLog.sub("cpui")
    private var previewOnly = false
    private var previewCaptured = false
    private var previewReady = false
    /** Preview-only slope override (`slope_test_m` extra): exercises wedge layout without the car. */
    private var previewSlopeM: Float? = null
    /** Layout-time strip: freed space above the 960-tall CarPlay picture. 0 = no custom bar. */
    private var topStripPx: Int = 0
    private var lastDisplayLogKey: String? = null
    private var lastTopBarLog: String? = null
    private var lastGeometryLog: String? = null
    private var lastMismatchLog: String? = null
    private var centerBlockX: Int = 0
    private var centerBlockY: Int = 0
    /** Downscale applied to the latched block when the live window shrank after SUBSCRIBE. 1 = 1:1. */
    private var windowFitScale: Float = 1f
    private var topBar: wasidremin.gmccpa.GmTopBarView? = null
    private var topBarData: wasidremin.gmccpa.TopBarVehicleData? = null
    /**
     * What each top-bar tile does. AAOS_* / CHARGING leave for the car's own apps
     * ([VehicleLaunch]); CP_* drive the CarPlay UI on the phone through the vision launcher
     * (AC Home is not usable: the box drops it while dPadSupport=false, 2026-10-15 log);
     * APP_SETTINGS / INSIGHTS bring this app's settings forward on the matching tab.
     */
    private fun requestBarTarget(target: wasidremin.gmccpa.BarTarget) {
        if (previewOnly) { log.i("topbar: preview request ${target.name.lowercase()} — no command sent"); return }
        when (target) {
            wasidremin.gmccpa.BarTarget.AAOS_HOME -> VehicleLaunch.home(this)
            wasidremin.gmccpa.BarTarget.CHARGING -> VehicleLaunch.charging(this)
            wasidremin.gmccpa.BarTarget.AAOS_SETTINGS -> VehicleLaunch.settings(this)
            wasidremin.gmccpa.BarTarget.CP_HOME -> launchOrFallback("home") {
                log.i("topbar: carplay home unavailable — nothing sent")
            }
            wasidremin.gmccpa.BarTarget.CP_MAPS -> launchOrFallback("maps") {
                log.i("topbar: maps templates missing — nothing sent")
            }
            wasidremin.gmccpa.BarTarget.CP_SPOTIFY -> launchOrFallback("spotify") {
                log.i("topbar: spotify templates missing — nothing sent")
            }
            wasidremin.gmccpa.BarTarget.CP_PHONE -> {
                val action = nowPlaying.phoneAction()
                if (action != null) {
                    touchHandler?.post {
                        val sent = wasidremin.gmccpa.ocbm.AdapterSession.sendTelephony(action)
                        log.i("topbar: phone button $action sent=$sent")
                    }
                } else {
                    launchOrFallback("phone") {
                        sendSiri()
                        log.i("topbar: phone templates missing — Siri voice-dial")
                    }
                }
            }
            wasidremin.gmccpa.BarTarget.APP_SETTINGS -> openSettings(tab = 0)
            wasidremin.gmccpa.BarTarget.INSIGHTS -> openSettings(tab = 3)
        }
    }

    private fun launchOrFallback(role: String, fallback: () -> Unit) {
        if (!wasidremin.gmccpa.av.VisionLauncher.isCalibrated(this, role)) {
            fallback()
            return
        }
        val started = wasidremin.gmccpa.av.VisionLauncher.launchAsync(this, role, "top-bar") { rec ->
            log.i("topbar: launch $role -> ${rec.outcome}" + (rec.error?.let { " ($it)" } ?: ""))
        }
        log.i("topbar: launch $role ${if (started) "started" else "ignored (busy/debounce)"}")
    }

    /**
     * Step 1: measure the window after the bars are applied and log the geometry once per
     * (window, screen mode). Step 2: place the custom top bar in the freed space.
     *
     * The strip was reserved before SUBSCRIBE. Hiding the status bar does not guarantee
     * additional window pixels: the Equinox's laid-out height was only 967, so subtracting
     * a hardcoded 960 produced a nearly invisible 7-pixel bar. Never renegotiate here.
     */
    private fun measureAndLayout() {
        if (isDestroyed) return
        val decor = window.decorView
        val mode = effectiveMode()
        val wm = windowManager
        val insets = if (Build.VERSION.SDK_INT >= 30) decor.rootWindowInsets else null
        val current = if (Build.VERSION.SDK_INT >= 30)
            wm.currentWindowMetrics.bounds.let { "${it.width()}x${it.height()}" } else "n/a"
        val max = if (Build.VERSION.SDK_INT >= 30)
            wm.maximumWindowMetrics.bounds.let { "${it.width()}x${it.height()}" } else "n/a"
        val status = insets?.getInsets(WindowInsets.Type.statusBars())
        val nav = insets?.getInsets(WindowInsets.Type.navigationBars())
        val statusVisible = insets?.isVisible(WindowInsets.Type.statusBars()) ?: true
        val navVisible = insets?.isVisible(WindowInsets.Type.navigationBars()) ?: true
        val key = "${System.identityHashCode(window)}:${mode.name}"
        if (lastDisplayLogKey != key) {
            lastDisplayLogKey = key
            log.i("display: window=${decor.width}x${decor.height} current=$current max=$max " +
                "statusBars=$status navBars=$nav statusVisible=$statusVisible navVisible=$navVisible")
        }
        val location = IntArray(2).also { root.getLocationOnScreen(it) }
        if (insets != null && root.width >= 640 && root.height >= 480) {
            VideoFrame.noteCarPlayWindow(this, mode, root.width, root.height,
                location[0], location[1], insets)
        }
        val wantBar = DisplayPrefs.customTopBar(this) &&
            (mode == ScreenMode.FULLSCREEN || mode == ScreenMode.STATUS_HIDDEN)
        val navBottom = if (navVisible) (nav?.bottom ?: 0) else 0
        val strip = if (wantBar) VideoFrame.topPx else 0
        val cutout = insets?.displayCutout
        val stableStatus = if (insets != null && Build.VERSION.SDK_INT >= 30)
            insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top else 0
        val visibleNav = if (navVisible) (nav?.bottom ?: 0) else 0
        val latchedW = VideoFrame.leftPx + VideoFrame.railPx + VideoFrame.viewWidth + VideoFrame.rightPx
        val latchedH = VideoFrame.topPx + VideoFrame.topInsetPx + VideoFrame.viewHeight + VideoFrame.bottomPx
        val fit = wasidremin.gmccpa.DisplayGeometry.fitLatched(root.width, root.height, latchedW, latchedH)
        centerBlockX = fit.x
        centerBlockY = fit.y
        windowFitScale = if (viewAreaIndex == 0) fit.scale else 1f
        val mismatchKey = "${System.identityHashCode(window)}:${root.width}x${root.height}:$latchedW x$latchedH:${fit.scale}"
        if (!fit.matched) {
            if (lastMismatchLog != mismatchKey) {
                lastMismatchLog = mismatchKey
                log.w("topbar geometry mismatch root=${root.width}x${root.height} latched=${latchedW}x$latchedH scale=${fit.scale}")
            }
        } else {
            lastMismatchLog = null
        }
        val maxBounds = if (Build.VERSION.SDK_INT >= 30)
            wm.maximumWindowMetrics.bounds.let { "${it.width()}x${it.height()}" } else "n/a"
        val cutoutText = "${cutout?.boundingRects ?: emptyList<android.graphics.Rect>()}/" +
            "${cutout?.safeInsetLeft ?: 0},${cutout?.safeInsetTop ?: 0}," +
            "${cutout?.safeInsetRight ?: 0},${cutout?.safeInsetBottom ?: 0}"
        val geometry = "display: mode=${mode.name} root=${root.width}x${root.height}@${location[0]},${location[1]} " +
            "max=$maxBounds cutoutPolicy=${window.attributes.layoutInDisplayCutoutMode} cutout=$cutoutText " +
            "stableStatus=$stableStatus visibleNav=$visibleNav strip=($strip,${VideoFrame.captureTopSource}) " +
            "bottom=${VideoFrame.bottomPx} advertised=${VideoFrame.width}x${VideoFrame.height} " +
            "video=${VideoFrame.viewWidth}x${VideoFrame.viewHeight} rail=${VideoFrame.railPx} " +
            "captureSource=${VideoFrame.captureSource}"
        if (lastGeometryLog != geometry) { lastGeometryLog = geometry; log.i(geometry) }
        val reason = when {
            !wantBar -> "custom top bar off"
            statusVisible -> "status bar transient"
            strip <= 0 -> "not reserved at subscription (reconnect to apply)"
            else -> "ok"
        }
        // Top-edge cutout wedge (Equinox: Rect(0,0-2629,167)) gives the panel slope the bar
        // hangs its content from; anything else (centered notch, none) means a flat panel.
        val wedge = cutout?.boundingRects
            ?.filter { it.top == 0 && it.left == 0 }
            ?.maxByOrNull { it.width() }
        val liveSlopeM = wasidremin.gmccpa.DisplayGeometry.wedgeSlopeM(
            wedge?.right ?: 0, wedge?.bottom ?: 0)
        val slopeM = if (previewOnly) previewSlopeM ?: liveSlopeM else liveSlopeM
        placeTopBar(strip, reason, maxOf(cutout?.safeInsetRight ?: 0, VideoFrame.rightPx), slopeM)
    }

    /**
     * Put GmTopBarView at y=0 in the freed strip; shift the rail + surface down by it.
     *
     * [rightInset] is the live `displayCutout.safeInsetRight` (285 on the Equinox EV's sloped /
     * cut right side). The bar spans the full window, but its clock + temperature cluster must
     * start left of the missing glass — the 2026-10-18 capture proved the rightmost items were
     * drawn at x 2700-2850, invisible on the car. Re-applied every layout: the cutout object is
     * only non-null once the window is laid out under the ALWAYS policy.
     */
    private fun placeTopBar(strip: Int, reason: String, rightInset: Int, slopeM: Float) {
        val placed = strip > 0 && viewAreaIndex == 0
        if (placed) {
            var bar = topBar
            if (bar == null) {
                bar = wasidremin.gmccpa.GmTopBarView(this)
                bar.onButton = { button ->
                    log.i("topbar: ${button.name} button requested")
                    requestBarTarget(button)
                }
                bar.elevation = dp(10).toFloat()
                val barH = (strip * windowFitScale).roundToInt().coerceAtLeast(1)
                root.addView(bar, android.widget.FrameLayout.LayoutParams(-1, barH, Gravity.TOP))
                topBar = bar
                topBarData = wasidremin.gmccpa.TopBarVehicleData(bar).also { it.start() }
                log.i("topbar: attached height=${strip}px (on-screen ${barH}px at scale=${windowFitScale}); clock follows system 12/24h setting")
            } else {
                val lp = bar.layoutParams as android.widget.FrameLayout.LayoutParams
                val barH = (strip * windowFitScale).roundToInt().coerceAtLeast(1)
                if (lp.height != barH) { lp.height = barH; bar.layoutParams = lp }
                bar.visibility = View.VISIBLE
            }
            if (bar.rightInsetPx != rightInset.coerceAtLeast(0)) bar.rightInsetPx = rightInset
            if (bar.slopeM != slopeM.coerceAtLeast(0f)) bar.slopeM = slopeM.coerceAtLeast(0f)
            val decor = window.decorView
            bar.outline = wasidremin.gmccpa.DisplayPrefs.glassOutline(
                this, decor.width.toFloat(), decor.height.toFloat())
            bar.visibility = if (window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.statusBars()) == true)
                View.GONE else View.VISIBLE
        } else {
            topBar?.visibility = View.GONE
        }
        topStripPx = if (placed) strip else 0
        syncChrome()
        val logKey = "$strip:$placed:$reason:${windowFitScale}:$centerBlockX,$centerBlockY:$rightInset:$slopeM"
        if (lastTopBarLog != logKey) {
            lastTopBarLog = logKey
            log.i("topbar: strip=${strip}px placed=$placed reason=$reason scale=${windowFitScale} center=$centerBlockX,$centerBlockY")
        }
        if (previewOnly && previewReady && !previewCaptured) {
            previewCaptured = true
            root.post {
                if (!isDestroyed) {
                    val safe = wasidremin.gmccpa.DisplayGeometry.content(VideoFrame.viewWidth,
                        VideoFrame.viewHeight, resources.displayMetrics.density, VideoFrame.controls)
                    val expected = if (VideoFrame.controls.fit) wasidremin.gmccpa.DisplayGeometry.fit(safe,
                        VideoFrame.width, VideoFrame.height) else safe
                    val fits = surfaceView.top == strip + VideoFrame.topInsetPx + expected.y + centerBlockY &&
                        surfaceView.height == expected.height && surfaceView.width == expected.width &&
                        surfaceView.left == VideoFrame.leftPx + VideoFrame.railPx + expected.x + centerBlockX &&
                        (strip == 0 || topBar?.height == strip)
                    log.i("topbar: preview layout ${if (fits) "PASS" else "FAIL"} " +
                        "root=${root.width}x${root.height} " +
                        "video=[${surfaceView.left},${surfaceView.top},${surfaceView.right},${surfaceView.bottom}] " +
                        "bar=${topBar?.height ?: 0}")
                }
            }
        }
    }

    companion object {
        /**
         * The live screen, so a session ending elsewhere can tear it down.
         *
         * A WEAK reference on purpose: this is a static field holding an Activity, and a strong one
         * would pin a finished Activity (and its Surface, decoder and window) for the life of the
         * process. Cleared in [onDestroy] too — the weak ref is the backstop, not the plan.
         */
        @Volatile private var live: java.lang.ref.WeakReference<CarPlayActivity>? = null

        /** A fresh preview must not reuse an old task's latched geometry. Main-thread only. */
        fun closePreview() {
            live?.get()?.let { if (it.previewOnly) it.finish() }
        }

        /** Live CarPlayActivity instance if one is up and not destroyed. */
        fun liveActivity(): CarPlayActivity? =
            live?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

        /** Live CarPlay SurfaceView when its holder has a valid Surface. */
        fun liveSurfaceView(): SurfaceView? {
            val act = liveActivity() ?: return null
            if (!act::surfaceView.isInitialized) return null
            val sv = act.surfaceView
            return sv.takeIf { it.holder?.surface?.isValid == true && it.width > 0 && it.height > 0 }
        }

        /**
         * The CarPlay session ended — take the screen down with it.
         *
         * # Why this exists
         *
         * Nothing used to tell this Activity a session had ended. `CarPlayRx.fireSessionDown` reached
         * `MainActivity.onCarPlaySessionDown`, which set `sessionUp = false`, poked the supervisor and
         * cleared the pairing code — and stopped there. The only `finish()` in this file was on a bind
         * failure. So when the phone went out of Wi-Fi range the pump timed out, the session was
         * correctly declared down, and **the last decoded frame stayed on the Surface indefinitely**:
         * a frozen CarPlay screen over a dead session, swallowing touches. Device-reported 2026-08-28.
         *
         * Finishing, rather than clearing the Surface and staying up, is deliberate — it is also what
         * fixes RESUME. `startSession` returns early on "session already started", so a stale live
         * Activity absorbs the returning phone's `onSessionUp` and the screen never rebuilds. Taking
         * it down means `MainActivity.launchCarPlayUi` starts a clean one, which is exactly what a
         * reconnect needs (a fresh generation, a fresh Surface, and a producer that re-dials with an
         * IDR).
         *
         * Safe to call from any thread and when no screen is up.
         */
        fun onSessionEnded(why: String) {
            val act = live?.get()
            if (act != null) act.endSession(why) else nowPlaying.clear("session ended")
        }

        /** View-area command from the USB metadata lane. The activity applies it if it is up. */
        /** Screen mode or the sidebar toggle changed on the launcher. Apply it if this screen is up. */
        fun refreshChrome() {
            val act = live?.get() ?: return
            act.runOnUiThread { act.applySystemUi() }
        }

        fun onAdapterViewArea(root: Any?) {
            val idx = BPlist.int(root, "params", "viewAreaIndex")?.toInt() ?: return
            val act = live?.get() ?: return
            if (idx !in VIEW_AREAS.indices) return
            act.runOnUiThread { act.applyViewArea(idx) }
        }

        /** The CarPlay home-screen tile. Brings settings forward and keeps the session up. */
        fun openSettingsFromIcon() {
            val act = live?.get()
            if (act != null) act.runOnUiThread { act.openSettings() }
            else DisplayPrefs.holdLauncher = true
        }

        /**
         * The merged now-playing picture. PROCESS-wide, not per-Activity: the MediaSession that
         * publishes it lives in [CarPlayMediaBrowserService], whose lifetime AAOS controls (it is
         * bound when Media Center asks, not when we start), so an Activity-instance field would be
         * unreachable from it. Session scope is restored by CLEARING it in [stopSession] instead.
         */
        val nowPlaying = NowPlayingState().apply {
            onMetadataChanged = { snapshot ->
                CarPlayMediaBrowserService.publish(snapshot)
                live?.get()?.let { act -> act.runOnUiThread { if (!act.isDestroyed) act.updateRailPlayback() } }
            }
            // The rail icon follows ticks too: status arrives in its own sparse frames, so
            // metadata-only refresh left the play/pause glyph stale for whole tracks.
            onPlaybackTick = {
                CarPlayMediaBrowserService.publishPlaybackState(it)
                live?.get()?.let { act -> act.runOnUiThread { if (!act.isDestroyed) act.updateRailPlayback() } }
            }
            // Cluster turn-by-turn rides the same seam and the same session end. Every teardown
            // path already clears this object, so hooking the clear covers all of them.
            onNavRecord = { kind, json -> wasidremin.gmccpa.nav.ClusterNav.onRecord(kind, json) }
            onCleared = { why -> wasidremin.gmccpa.nav.ClusterNav.onSessionEnded(why) }
        }

        /**
         * The declared view areas, in `/info` order. MUST stay in step with
         * `tools/info_plist_viewareas.py` and the priming block in `native/carplay-jni/src/lib.rs` —
         * three places, one geometry, because this app serves a STATIC /info and nothing derives one
         * from the others.
         *
         * [0] the full panel. [1] the box AAOS actually gives an ordinary app on this head unit
         * (`mAppBounds` 1416x842, origin moved from x=189 to 188 because iOS's validator requires all
         * four values even and an odd one is a teardown, not a warning).
         */
        val VIEW_AREAS = listOf(
            android.graphics.Rect(0, 0, 2400, 960),
            android.graphics.Rect(188, 118, 188 + 1416, 118 + 842),
        )
        /** The duration the receiver tells iOS the transition takes (`events::send_update_view_area`). */
        const val VIEW_AREA_ANIM_MS = 3000L

        /** Must match `/info` displays[] — the space iOS renders into and expects touch in. */
        const val DISPLAY_W = 2400
        const val DISPLAY_H = 960
        // Bind runs on the UI thread in onCreate; keep the worst case (4 ports x RETRIES x BACKOFF)
        // safely under the ~5 s ANR threshold. 4 x 6 x 150 ms = 3.6 s max, and normal binds are instant.
        /** Settle time between the picture coming back and a settings-originated CarPlay tile acting. */
        private const val BAR_TARGET_DELAY_MS = 900L
        private const val BIND_RETRIES = 6
        private const val BIND_BACKOFF_MS = 150L
        /** Desync guard on the seam length prefix; matches HevcRenderer and session.rs MAX_FRAME_BODY. */
        private const val MAX_MESSAGE = 8 * 1024 * 1024
        /** Internal phase for ACTION_CANCEL. The wire only knows 0/1/2 — see [onTouch]. */
        private const val PHASE_CANCEL = 3
        /** Displacement sent before a cancelled gesture's UP, as a fraction of the view width. ~2% is
         *  comfortably past iOS's tap allowable-movement and still an imperceptible drag. */
        private const val CANCEL_SLOP_N = 0.02f

        /**
         * [SessionTrace] name for "the producer dialled :9001". ONE name, armed from two edges that
         * both mean "a video connection is now owed":
         *
         *  - the first `/command` plist of a generation ([onCommandPlist]) with no video connection
         *    live — iOS sends it on the event channel accepted at RECORD, and SETUPs the screen
         *    stream right after (2026-09-09: RECORD 22:55:22.072, first `modesChanged` .100, SETUP
         *    type-110 .299, `video seam connected` .802 — **730 ms**). Budget [VIDEO_CONNECT_MS].
         *  - a live :9001 connection bounced by [attachRenderer], after which forward.rs re-dials on
         *    its next frame and the ForceKeyFrame just sent produces one. Budget [VIDEO_REDIAL_MS].
         *
         * NOT armed from the Activity launch itself: `launchCarPlayUi` also runs from the bench
         * `carplay_ui` command and from `onResume`, and the reference session shows this screen up
         * from 22:51:14 with no phone until 22:55:20 — a launch-anchored expectation would fire on
         * every bench start. Nor from the metadata seam connect, whose producer is not proven to be
         * post-RECORD on every transport. The plist is byte-proven post-RECORD.
         *
         * `av/`-scoped: [stopSession] drops it with the rest when the AirPlay session ends.
         */
        private const val EXPECT_VIDEO_CONNECT = "av/video-seam-connect"
        /** 730 ms measured; the screen SETUP is iOS-initiated immediately after RECORD, unlike the
         *  type-102 media stream (8.5 s later in the same session, on demand — deliberately NOT armed). */
        private const val VIDEO_CONNECT_MS = 10_000L
        /** One `connect_timeout(2 s)` ceiling plus a frame from the requested IDR, with margin. */
        private const val VIDEO_REDIAL_MS = 5_000L
        /** [SessionTrace.Board] names for the four seams and the Surface-scoped renderer. */
        private const val BOARD_VIDEO = "seam-video"
        private const val BOARD_MEDIA = "seam-media"
        private const val BOARD_VOICE = "seam-voice"
        private const val BOARD_META = "seam-meta"
        private const val BOARD_RENDERER = "video-renderer"
        /** Wired-path video opens. Non-zero means this session already had a surface, so a
         *  recreated activity is a re-attach and must not replay the hand-off animation. */
        @Volatile private var wiredVideoEpoch = 0

        private fun boardFor(label: String): String = when (label) {
            "video" -> BOARD_VIDEO; "audio" -> BOARD_MEDIA; "voice" -> BOARD_VOICE; else -> BOARD_META
        }
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var root: android.widget.FrameLayout
    /** The settings gear, or the sidebar rail when that option is on. Null until the first layout. */
    private var chrome: View? = null
    /** Which chrome is currently attached, so a focus callback does not rebuild it every time. */
    private var chromeIsRail: Boolean? = null
    /** Top-bar state the current rail was built for; a change flips Home/Settings on the rail. */
    private var chromeTopBarOn: Boolean = false
    /** Index into [VIEW_AREAS] the surface is currently laid out for. */
    @Volatile private var viewAreaIndex = 0
    /** Cancels a pending shrink if another transition arrives first. */
    private var viewAreaGen = 0
    // @Volatile is load-bearing: written on the UI thread (attach/detachRenderer, stopSession) and
    // read on the cp-video thread. Without it the serve thread may legally observe a stale null after a
    // Surface returns — a permanent black screen plus the discard path below — or a stale stopped
    // renderer after a detach.
    @Volatile private var renderer: HevcRenderer? = null
    private var player: AacPlayer? = null
    @Volatile private var voiceRouter: VoiceRouter? = null
    @Volatile private var micUplink: MicUplink? = null

    private val servers = CopyOnWriteArrayList<ServerSocket>()
    private val liveSockets = CopyOnWriteArrayList<Socket>()

    /**
     * Per-generation liveness. A single shared flag cannot distinguish "my generation was stopped"
     * from "a new generation started", which lets a stale listener thread bind the port and feed a
     * released decoder while the new generation gets EADDRINUSE — listeners alive, zero video.
     */
    private var generation: AtomicBoolean? = null

    /** Per generation: [EXPECT_VIDEO_CONNECT] is armed from the first `/command` plist at most once.
     *  Written on `cp-meta` and the UI thread (reset in [startSession]); volatile is enough. */
    @Volatile private var videoConnectArmed = false
    /** Latched so a dead event channel logs one W per run of refused ForceKeyFrames, not one per request. */
    @Volatile private var keyframeReqFailed = false

    /** Touch must not run on the UI thread: a send can block on the event-channel lock for seconds. */
    private var touchThread: HandlerThread? = null
    private var touchHandler: Handler? = null
    // AtomicReference, not a plain @Volatile: the check-set-post sequence on a plain field has a
    // lost-wakeup race (reader nulls it between the UI thread's read and post) that freezes the rest
    // of a drag. getAndSet makes the drain atomic and self-coalescing.
    private val pendingMove = AtomicReference<Triple<Int, Float, Float>?>(null)
    private var primaryPointerId = -1
    /** Hand-off overlay. Absent when this open is a Surface re-attach of a session that already played. */
    private var intro: StartupAnimationView? = null
    private var introGear: View? = null
    private var introFinished = false
    private val introLate = Runnable { finishIntro(late = true) }

    /**
     * The settings screen asked for a CarPlay action (a top-bar CarPlay tile tapped there). Runs
     * once the picture has had time to come back: the vision launcher needs a live, stable frame.
     */
    private fun runPendingBarTarget(i: Intent?) {
        val name = i?.getStringExtra(wasidremin.gmccpa.EXTRA_BAR_TARGET) ?: return
        i.removeExtra(wasidremin.gmccpa.EXTRA_BAR_TARGET)
        val target = runCatching { wasidremin.gmccpa.BarTarget.valueOf(name) }.getOrNull() ?: return
        log.i("topbar: ${target.name} requested from settings — running once the picture is up")
        root.postDelayed({ if (!isDestroyed) requestBarTarget(target) }, BAR_TARGET_DELAY_MS)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        runPendingBarTarget(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewOnly = intent.getBooleanExtra("topbar_preview", false)
        if (previewOnly && intent.hasExtra("slope_test_m"))
            previewSlopeM = intent.getFloatExtra("slope_test_m", 0f).coerceAtLeast(0f)
        live = java.lang.ref.WeakReference(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surfaceView = SurfaceView(this)
        // The surface is no longer the content view: it is a POSITIONED child of a panel-sized root,
        // so it can occupy a view area smaller than the panel and let GM's own chrome show around it.
        // The root stays full-bleed (2400x960) and is the coordinate space touch is normalised in —
        // see [onTouch]. Its background is what shows outside the active view area.
        root = android.widget.FrameLayout(this)
        root.addView(surfaceView, android.widget.FrameLayout.LayoutParams(VideoFrame.viewWidth, VideoFrame.viewHeight, Gravity.TOP or Gravity.START).apply {
            marginStart = VideoFrame.railPx
        })
        // The view stays the on-screen rectangle. The buffer is the advertised size, so the
        // compositor scales the picture up. Touch is normalised to the view.
        surfaceView.holder.setFixedSize(VideoFrame.width, VideoFrame.height)
        setContentView(root)
        runPendingBarTarget(intent)
        if (Build.VERSION.SDK_INT >= 30) {
            // If GM's bar comes back (a swipe shows the bars transiently), the freed space is
            // gone: hide our bar until the next applySystemUi re-measures. Re-hiding GM's bars
            // stays on onResume / onWindowFocusChanged.
            window.decorView.setOnApplyWindowInsetsListener { v, insets ->
                val transientVisible = insets.isVisible(WindowInsets.Type.statusBars())
                if (transientVisible && topStripPx > 0 && topBar?.visibility != View.GONE) {
                    log.i("topbar: GM status bar reappeared — hiding custom bar without moving video")
                    topBar?.visibility = View.GONE
                } else if (!transientVisible && topStripPx > 0 && topBar?.visibility != View.VISIBLE) {
                    log.i("topbar: GM status bar hidden — showing custom bar at stable geometry")
                    topBar?.visibility = View.VISIBLE
                }
                v.onApplyWindowInsets(insets)
            }
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> measureAndLayout() }
        applySystemUi()
        root.setOnApplyWindowInsetsListener { v, insets ->
            val mode = effectiveMode()
            val location = IntArray(2).also { root.getLocationOnScreen(it) }
            VideoFrame.noteCarPlayWindow(this, mode, root.width, root.height,
                location[0], location[1], insets)
            v.onApplyWindowInsets(insets)
        }
        root.requestApplyInsets()
        if (previewOnly) root.post {
            val location = IntArray(2).also { root.getLocationOnScreen(it) }
            VideoFrame.noteCarPlayWindow(this, effectiveMode(), root.width, root.height,
                location[0], location[1], root.rootWindowInsets)
            VideoFrame.capture(this, advertise = true)
            previewReady = true
            measureAndLayout()
        }
        root.setBackgroundColor(android.graphics.Color.BLACK)
        VideoPresentation.onSizeChanged = { root.post { if (!isDestroyed) applyVideoFrame() } }
        if (DisplayPrefs.controls(this).stats) {
            root.addView(ProjectionStatsPill(this, previewOnly), android.widget.FrameLayout.LayoutParams(-2, -2))
        }
        // In-call overlay pill: added once, above the picture and the rail, driven by
        // NowPlayingState.callPhase via updateCallPill. It positions itself from persisted
        // fractions once the root has a size; see CallPillView.bind.
        callPill = CallPillView(this).also {
            it.elevation = dp(32).toFloat()
            root.addView(it, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START))
        }
        // Hardware-free look at the in-call pill: `call_preview` = ring | active | muted.
        if (previewOnly) intent.getStringExtra("call_preview")?.let { v ->
            callPill?.bind(if (v == "ring") CallPhase.RINGING else CallPhase.ACTIVE, v == "muted", {}, {}, {})
        }
        if (!previewOnly) maybeShowIntro()

        touchThread = HandlerThread("cp-touch").also { it.start(); touchHandler = Handler(it.looper) }

        // Seams + audio start HERE, not on surfaceCreated: they must outlive the Surface. Anything
        // that backgrounds this Activity (a GM dialog, reverse gear, a stray `am start`) destroys the
        // Surface, and if the sockets went with it the CarPlay session would be gone for good.
        if (!previewOnly) {
            if (wasidremin.gmccpa.ocbm.AdapterWifi.enabled(this)) startAdapterSession() else startSession()
        } else log.i("topbar: preview only — no session, renderer, audio or USB claim")

        surfaceView.setOnTouchListener { v, ev -> onTouch(v, ev) }
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { if (!previewOnly) attachRenderer(h) }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {
                log.i("surface ${w}x$ht (advertised ${VideoFrame.width}x${VideoFrame.height}, view ${VideoFrame.viewWidth}x${VideoFrame.viewHeight}, rail ${VideoFrame.railPx})")
                // The view system owns this SurfaceControl and resets its geometry on relayout, so a
                // non-default view area has to be re-asserted here or the picture silently goes
                // full-bleed again after any layout pass.
                if (viewAreaIndex != 0) applyGeometry(declaredViewArea(viewAreaIndex))
            }
            override fun surfaceDestroyed(h: SurfaceHolder) { detachRenderer() }
        })
    }

    /**
     * System-bar VISIBILITY follows the view area. The LAYOUT is never touched.
     *
     * ## The distinction that matters
     * On this head unit AAOS insets the CONTENT, never the window: `mBounds` is the full 2400x960
     * panel in both states and `mWindowingMode=fullscreen` throughout
     * (`evidence/drive_20260818-132220/headunit.log:5762`). GM's LeftBar and TopCarSystemBar are
     * separate system-UI windows at a higher Z that OVERLAY our window — that is the default, not an
     * exception, and `docs/06_BRINGUP_RUNBOOK.md:352-354` measured it: "App window 2400x960 — full
     * panel", "System bar insets, normal left=189 top=118 — GM chrome overlays the panel".
     *
     * So the only thing that has to change per area is whether the bars are HIDDEN. The three LAYOUT_*
     * flags stay set permanently: they are what keep the decor from padding our content. Clearing
     * them alongside the hide bits moved the root to (189,118) and displaced the fixed 2400x960
     * surface — device-observed 2026-09-08 as the picture landing at 377,236. One line was needed and
     * two were changed.
     *
     * `FLAG_FULLSCREEN` has to move too. It comes from the theme (`Theme.NoTitleBar.Fullscreen`) and
     * suppresses the status bar INDEPENDENTLY of `systemUiVisibility`: clearing the sysui bits alone
     * brought the LeftBar back (`isReadyForDisplay=true`) while TopCarSystemBar stayed hidden behind
     * this flag.
     *
     * ## Geometry stays static
     * Both rects are known constants for this panel ([VIEW_AREAS]) — this app targets one radio and
     * one display, so nothing is measured, awaited or renegotiated at runtime.
     */
    /**
     * Which bars to hide. View area 1 is the CarPlay dock-resize contract: reveal GM's chrome,
     * whatever the driver picked for the full panel. Area 0 uses the saved screen mode.
     */
    private fun effectiveMode(): ScreenMode =
        if (viewAreaIndex != 0) ScreenMode.SYSTEM_UI
        else DisplayPrefs.mode(this)

    private fun applySystemUi() {
        val mode = effectiveMode()
        DisplayPrefs.applyWindow(this, mode)
        // Measure after the bars are applied: the insets the strip depends on are only valid
        // once the decor has laid out with the new visibility.
        window.decorView.requestApplyInsets()
        window.decorView.post { measureAndLayout() }
        syncChrome()
    }

    /**
     * Settings gear, or the left rail.
     *
     * On an adapter session the rail is [VideoFrame.railPx] wide and the surface is the remainder,
     * so the rail occupies a gap the phone is not drawing into. The in-process path has no way to
     * change the static 2400×960 advertisement, so its rail stays an overlay.
     */
    private fun syncChrome() {
        val resized = VideoFrame.railPx > 0 && viewAreaIndex == 0
        val rail = resized || (DisplayPrefs.sidebar(this) && viewAreaIndex == 0 && !wasidremin.gmccpa.ocbm.AdapterWifi.enabled(this))
        applyVideoFrame()
        // Keep the current chrome unless the kind changed, or the rail's Home/Settings visibility
        // (driven by the top-bar toggle) no longer matches what it was built for.
        val railStale = rail && chromeTopBarOn != DisplayPrefs.customTopBar(this)
        if (chromeIsRail == rail && chrome != null && !railStale) {
            chrome!!.layoutParams = chromeParams(rail)
            return
        }
        chromeIsRail = rail
        chrome?.let { root.removeView(it) }
        val view = if (rail) buildRail() else buildGear()
        chrome = view
        view.elevation = dp(8).toFloat()
        root.addView(view, chromeParams(rail))
    }

    /**
     * Rail or gear layout. When the custom bar owns the freed strip, the rail sits at y=strip
     * and is exactly the CarPlay block tall; otherwise it keeps today's full-height geometry.
     */
    private fun chromeParams(rail: Boolean): android.widget.FrameLayout.LayoutParams {
        return if (rail) {
            val s = if (viewAreaIndex == 0) windowFitScale else 1f
            val railW = if (VideoFrame.railPx > 0 && viewAreaIndex == 0) (VideoFrame.railPx * s).roundToInt().coerceAtLeast(1) else dp(VideoFrame.RAIL_DP)
            val railH = if (viewAreaIndex == 0) (VideoFrame.viewHeight * s).roundToInt().coerceAtLeast(1) else VideoFrame.viewHeight
            android.widget.FrameLayout.LayoutParams(railW, railH,
                Gravity.TOP or Gravity.START).apply {
                if (viewAreaIndex == 0) {
                    topMargin = centerBlockY + ((topStripPx + VideoFrame.topInsetPx) * s).roundToInt()
                    marginStart = centerBlockX + (VideoFrame.leftPx * s).roundToInt()
                }
            }
        } else {
            // Top-left, same corner as the other app's Settings button. Top-right is where CarPlay
            // draws Home / Work / Now Playing, and a control there covers those cards.
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply {
                val s = if (viewAreaIndex == 0) windowFitScale else 1f
                topMargin = dp(16) + if (viewAreaIndex == 0) ((topStripPx + VideoFrame.topInsetPx) * s).roundToInt() else 0
                marginStart = dp(16) + if (viewAreaIndex == 0) (VideoFrame.leftPx * s).roundToInt() else 0
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    /**
     * Place the surface at the on-screen CarPlay rectangle and fix the buffer at the advertised
     * size. The view is not scaled in software: [SurfaceHolder.setFixedSize] is what the
     * compositor enlarges. Area 0 is that rectangle. A later view area uses the scaled template.
     */
    private fun applyVideoFrame() {
        val lp = surfaceView.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        val rail = VideoFrame.railPx > 0 && viewAreaIndex == 0
        val top = topStripPx > 0 && viewAreaIndex == 0
        val traced = (VideoFrame.leftPx > 0 || VideoFrame.rightPx > 0 || VideoFrame.topInsetPx > 0 || VideoFrame.bottomPx > 0) && viewAreaIndex == 0
        val scaled = VideoFrame.uiScalePercent != 100 && viewAreaIndex == 0
        val viewW = when {
            viewAreaIndex != 0 -> declaredViewArea(viewAreaIndex).width()
            else -> VideoFrame.viewWidth
        }
        val viewH = when {
            viewAreaIndex != 0 -> declaredViewArea(viewAreaIndex).height()
            else -> VideoFrame.viewHeight
        }
        val bufW = if (viewAreaIndex == 0 && (rail || top || traced || scaled)) VideoFrame.width else viewW
        val bufH = if (viewAreaIndex == 0 && (rail || top || traced || scaled)) VideoFrame.height else viewH
        // Flush against the rail. The on-screen width is the measured window minus the rail, so
        // this rectangle and the rail together are the whole window.
        val gravity = Gravity.TOP or Gravity.START
        // windowFitScale shrinks the whole latched block (rail + strip + presentation) when the
        // live window came up smaller than the subscribe-time latch. The buffer stays advertised
        // so the compositor scales; touch stays normalised to the view so it stays correct.
        val s = if (viewAreaIndex == 0) windowFitScale else 1f
        val leftPx = if (viewAreaIndex == 0) (VideoFrame.leftPx * s).roundToInt() else 0
        val railPx = if (rail) (VideoFrame.railPx * s).roundToInt() else 0
        val stripPx = if (top) (topStripPx * s).roundToInt() else 0
        val topInsetPx = if (viewAreaIndex == 0) (VideoFrame.topInsetPx * s).roundToInt() else 0
        val inset = if (viewAreaIndex == 0) centerBlockX + leftPx + railPx else 0
        val topInset = centerBlockY + stripPx + topInsetPx
        val c = VideoFrame.controls
        val safe = wasidremin.gmccpa.DisplayGeometry.content(viewW, viewH, resources.displayMetrics.density, c)
        val presentation = if (c.fit) wasidremin.gmccpa.DisplayGeometry.fit(safe,
            VideoPresentation.width.takeIf { it > 0 } ?: bufW,
            VideoPresentation.height.takeIf { it > 0 } ?: bufH) else safe
        val outW = (presentation.width * s).roundToInt().coerceAtLeast(1)
        val outH = (presentation.height * s).roundToInt().coerceAtLeast(1)
        val outX = (presentation.x * s).roundToInt()
        val outY = (presentation.y * s).roundToInt()
        val x = inset + outX
        val y = topInset + outY
        if (lp.width == outW && lp.height == outH && lp.gravity == gravity &&
            lp.marginStart == x && lp.topMargin == y) return
        lp.width = outW
        lp.height = outH
        lp.gravity = gravity
        lp.marginStart = x
        lp.topMargin = y
        surfaceView.layoutParams = lp
        surfaceView.holder.setFixedSize(bufW, bufH)
        log.i("display: presentation=${if (c.fit) "fit" else "stretch"} available=${viewW}x$viewH " +
            "insetsDp=${c.left},${c.top},${c.right},${c.bottom} density=${resources.displayMetrics.density} " +
            "safe=${safe.width}x${safe.height}@${safe.x},${safe.y} " +
            "surface=${lp.width}x${lp.height}@$x,$y buffer=${bufW}x$bufH " +
            "ui=${c.uiPercent}% physical=${VideoFrame.physicalWidthMm}x${VideoFrame.physicalHeightMm}mm")
    }

    /** [VIEW_AREAS] at 100%. Otherwise the same rectangles divided by the UI scale. Area 0 is the advertised frame. */
    private fun declaredViewArea(index: Int): android.graphics.Rect {
        val t = VIEW_AREAS[index]
        val percent = VideoFrame.uiScalePercent
        if (index == 0) return android.graphics.Rect(0, 0, VideoFrame.width, VideoFrame.height)
        if (percent == 100) return t
        val x = wasidremin.gmccpa.DisplayScale.even(t.left, percent)
        val y = wasidremin.gmccpa.DisplayScale.even(t.top, percent)
        val w = wasidremin.gmccpa.DisplayScale.even(t.width(), percent)
        val h = wasidremin.gmccpa.DisplayScale.even(t.height(), percent)
        return android.graphics.Rect(x, y, x + w, y + h)
    }

    /** The control the driver uses to get back to settings without ending the session. */
    private fun buildGear(): View {
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xE6101214.toInt())
                cornerRadius = dp(40).toFloat()
            }
            setPadding(dp(8), dp(6), dp(20), dp(6))
            isClickable = true
            contentDescription = "Settings"
            setOnClickListener { openSettings() }
        }
        chip.addView(GearButton(this) { openSettings() }, LinearLayout.LayoutParams(dp(56), dp(56)))
        chip.addView(android.widget.TextView(this).apply {
            text = "Settings"
            setTextColor(0xFFF2F4F5.toInt())
            textSize = 18f
            setPadding(dp(6), 0, 0, 0)
        })
        return chip
    }

    private fun buildRail(): View {
        // Home and Settings duplicate tiles the custom top bar already carries. When the bar is on
        // they are dropped from the rail; the voice / play / phone trio — which the bar does not
        // provide — always stays. Tracked so syncChrome rebuilds the rail when the toggle changes.
        val topBarOn = DisplayPrefs.customTopBar(this)
        chromeTopBarOn = topBarOn
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // Same surface as the other app's dark scheme (`Theme.kt` surface).
            setBackgroundColor(0xFF0E1415.toInt())
            val h = dp(12)
            setPadding(h, dp(16), h, dp(16))
        }
        val top = railGroup()
        val bottom = railGroup()
        val gap = dp(20)
        // Fixed, equal-sized circular buttons. The mic is NOT in here: it fills the space between
        // the groups via layout weight, so it is excluded from the disc sizing below.
        val discs = mutableListOf<View>()
        fun add(parent: LinearLayout, child: View, first: Boolean) {
            discs.add(child)
            parent.addView(child, LinearLayout.LayoutParams(dp(96), dp(96)).apply {
                if (!first) topMargin = gap
                gravity = Gravity.CENTER_HORIZONTAL
            })
        }

        if (!topBarOn) {
            add(top, railButton(RailIcon.HOME, "AAOS Home", RailTone.Default) { _ -> openHome() }, true)
            add(top, railButton(RailIcon.SETTINGS, "Settings", RailTone.Default) { _ -> openSettings() }, false)
        }

        // The mic (Siri) is the always-present primary: a tall capsule that grows into the rail's
        // freed middle so it is the largest, easiest target instead of a lone disc above a gap.
        val voice = railButton(RailIcon.VOICE, "Voice Assistant", RailTone.Default, capsule = true) { _ -> sendSiri() }

        val play = railButton(RailIcon.PLAY, "Play", RailTone.Highlight) { _ ->
            val index = when {
                nowPlaying.snapshot.playing -> wasidremin.gmccpa.ocbm.Ocbm.MEDIA_BTN_PAUSE
                nowPlaying.snapshot.playbackStatus == null -> wasidremin.gmccpa.ocbm.Ocbm.MEDIA_BTN_PLAY_PAUSE
                else -> wasidremin.gmccpa.ocbm.Ocbm.MEDIA_BTN_PLAY
            }
            sendMedia(index)
        }
        railPlayButton = play
        updateRailPlayback()
        val phone = railButton(RailIcon.PHONE, "Phone — no call", RailTone.Default) { _ ->
            val action = nowPlaying.phoneAction()
            if (previewOnly) log.i("phone preview: $action")
            else if (action != null) touchHandler?.post {
                val sent = wasidremin.gmccpa.ocbm.AdapterSession.sendTelephony(action)
                log.i("phone button $action sent=$sent")
            }
        }
        fun updatePhone() {
            val action = nowPlaying.phoneAction()
            phone.icon = if (action == wasidremin.gmccpa.ocbm.Ocbm.TEL_END) RailIcon.BACK else RailIcon.PHONE
            phone.contentDescription = when (action) {
                wasidremin.gmccpa.ocbm.Ocbm.TEL_ANSWER -> "Answer call"
                wasidremin.gmccpa.ocbm.Ocbm.TEL_END -> "End call"
                else -> "Phone — no call"
            }
            phone.isEnabled = action != null || previewOnly
        }
        updatePhone()
        nowPlaying.onCallChanged = { runOnUiThread { if (!isDestroyed) { updatePhone(); updateCallPill() } } }
        add(bottom, play, true)
        add(bottom, phone, false)

        // Discs are square and equal; the capsule matches their width, its height being the weighted
        // remainder. Discs shrink only if the rail is too short to seat them all plus a disc of mic.
        rail.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val n = discs.size
            val slots = n + 1   // + the mic's minimum
            val avail = rail.height - dp(32) - gap * slots
            val size = minOf(dp(96), (avail / slots).coerceAtLeast(1))
            (discs + voice).forEach { button ->
                val lp = button.layoutParams as LinearLayout.LayoutParams
                if (lp.width != size || (button !== voice && lp.height != size)) {
                    button.layoutParams = lp.apply {
                        width = size
                        if (button !== voice) height = size
                    }
                }
            }
        }

        rail.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        // The mic capsule IS the spacer now — weight 1 so it absorbs all the room between the groups.
        rail.addView(voice, LinearLayout.LayoutParams(dp(96), 0, 1f).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = gap
            bottomMargin = gap
        })
        rail.addView(bottom, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return rail
    }

    private var railPlayButton: RailButton? = null
    /** Movable in-call overlay. Null until onCreate; the pill itself hides when idle. */
    private var callPill: CallPillView? = null
    /** Last mute state we sent via TEL_MUTE — iOS sends no mute read-back, so this is the label. */
    private var pillMuted = false

    /**
     * Shows/hides the in-call pill from [NowPlayingState.callPhase]. Answer sends TEL_ANSWER,
     * Hang up sends TEL_END, Mute toggles TEL_MUTE — the same path as the rail phone button,
     * which stays as the at-a-glance indicator while the pill carries the large touch targets.
     */
    private fun updateCallPill() {
        val pill = callPill ?: return
        val phase = nowPlaying.callPhase()
        pill.bind(
            phase, pillMuted,
            onAnswer = { sendCallButton(wasidremin.gmccpa.ocbm.Ocbm.TEL_ANSWER) },
            onEnd = { sendCallButton(wasidremin.gmccpa.ocbm.Ocbm.TEL_END) },
            onMuteToggle = {
                pillMuted = !pillMuted
                sendCallButton(wasidremin.gmccpa.ocbm.Ocbm.TEL_MUTE)
                runOnUiThread { updateCallPill() }
            },
        )
    }

    private fun sendCallButton(action: Byte) {
        if (previewOnly) {
            log.i("call pill preview: $action")
            return
        }
        touchHandler?.post {
            val sent = wasidremin.gmccpa.ocbm.AdapterSession.sendTelephony(action)
            log.i("call pill $action sent=$sent")
        }
    }

    @Volatile private var lastRailPlaying: Boolean? = null
    private fun updateRailPlayback() {
        val playing = nowPlaying.snapshot.playing
        if (playing == lastRailPlaying) return
        lastRailPlaying = playing
        railPlayButton?.let {
            it.icon = if (playing) RailIcon.PAUSE else RailIcon.PLAY
            it.contentDescription = if (playing) "Pause" else "Play"
        }
    }

    private fun railGroup() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun railButton(
        icon: RailIcon, description: String, tone: RailTone,
        capsule: Boolean = false, onClick: (RailButton) -> Unit,
    ) = RailButton(this, icon, description, tone, capsule, onClick)

    /**
     * Bring the launcher forward and restart the wireless session. The launcher stays up
     * ([DisplayPrefs.holdLauncher]) so resume does not cover the restart with the picture.
     */
    private fun requestSessionRestart() {
        log.i("rail reset — restarting the session")
        DisplayPrefs.holdLauncher = true
        startActivity(
            Intent(this, wasidremin.gmccpa.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(wasidremin.gmccpa.EXTRA_RESTART_SESSION, true)
        )
    }

    /**
     * Bring the launcher forward and keep it there. MainActivity's resume path otherwise treats a
     * return to the app as "the picture should come back" and covers settings immediately.
     */
    private fun openSettings(tab: Int = -1) {
        DisplayPrefs.holdLauncher = true
        log.i("settings — holding the launcher in front; the session stays up" +
            if (tab >= 0) " (tab $tab)" else "")
        log.i(AacPlayer.settingsFocusLine())
        startActivity(
            Intent(this, wasidremin.gmccpa.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .apply { if (tab >= 0) putExtra(wasidremin.gmccpa.EXTRA_OPEN_TAB, tab) }
        )
    }

    private fun openHome() {
        log.i("home — leaving the picture; the session stays up")
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Media keys share the event-channel lock with touch. Never send them on the UI thread. */
    private fun sendMedia(index: Byte) {
        if (previewOnly) { log.i("rail preview: media $index — no command sent"); return }
        CarPlayMediaBrowserService.userMediaButton(index)
    }

    /** Siri is a press and a release. A bare requestSiri is ignored by iOS. */
    private fun sendSiri() {
        if (previewOnly) { log.i("rail preview: Siri — no command sent"); return }
        val h = touchHandler ?: return
        h.post {
            if (!dispatchCommand(wasidremin.gmccpa.ocbm.Ocbm.CMD_SIRI_DOWN)) return@post
            h.postDelayed({ dispatchCommand(wasidremin.gmccpa.ocbm.Ocbm.CMD_SIRI_UP) }, 120)
        }
    }

    private fun sendNav(nav: Byte) {
        if (previewOnly) { log.i("rail preview: nav $nav — no command sent"); return }
        touchHandler?.post { dispatchNav(nav) }
    }

    /** Used by CarPlayFrameGrabber / CarPlayLauncher to send AC Home over the existing nav path. */
    fun sendAcHomeFromTooling() {
        sendNav(wasidremin.gmccpa.ocbm.Ocbm.NAV_HOME)
    }

    /**
     * Whole-window screenshot: the Android view hierarchy (custom top bar, rail/gear, background)
     * with the live CarPlay video frame drawn at the surface's on-screen rect.
     *
     * Calibration captures use PixelCopy straight off the SurfaceView, which is only the decoded
     * iPhone frame — the top bar and rail are sibling Views and never appear in it. This draws
     * [root] to a bitmap (the SurfaceView paints blank there) and overdraws [video] scaled into
     * the surface's live rect, so a reviewer sees the chrome around the picture.
     *
     * Safe to call from any thread: the hierarchy draw is marshalled to the UI thread. Returns
     * null when the window is not laid out. The caller owns the returned bitmap and must recycle it.
     */
    fun captureWindowComposite(video: android.graphics.Bitmap): android.graphics.Bitmap? {
        if (!::root.isInitialized || !::surfaceView.isInitialized) return null
        var hier: android.graphics.Bitmap? = null
        var left = 0; var top = 0; var w = 0; var h = 0
        val drawHierarchy = {
            try {
                if (!isFinishing && !isDestroyed && root.width > 0 && root.height > 0) {
                    left = surfaceView.left; top = surfaceView.top
                    w = surfaceView.width; h = surfaceView.height
                    hier = android.graphics.Bitmap.createBitmap(
                        root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
                    root.draw(android.graphics.Canvas(hier!!))
                }
            } catch (_: Throwable) { hier = null }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            drawHierarchy()
        } else {
            val latch = java.util.concurrent.CountDownLatch(1)
            runOnUiThread { try { drawHierarchy() } finally { latch.countDown() } }
            try {
                latch.await(2000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return null }
        }
        val base = hier ?: return null
        if (w <= 0 || h <= 0) return base
        runCatching {
            android.graphics.Canvas(base).drawBitmap(video, null,
                android.graphics.Rect(left, top, left + w, top + h), null)
        }.onFailure { log.w("full-window composite: video overdraw failed: ${it.message}") }
        return base
    }

    private fun dispatchCommand(cmd: Byte): Boolean {
        val sent = if (wasidremin.gmccpa.ocbm.AdapterWifi.enabled(this))
            wasidremin.gmccpa.ocbm.AdapterSession.sendCommand(cmd)
        else false
        if (!sent) log.w("command $cmd refused — no live adapter session")
        else log.i("command $cmd sent")
        return sent
    }

    private fun dispatchNav(nav: Byte): Boolean {
        val sent = if (wasidremin.gmccpa.ocbm.AdapterWifi.enabled(this))
            wasidremin.gmccpa.ocbm.AdapterSession.sendNav(nav)
        else false
        if (!sent) log.w("nav $nav refused — no live adapter session")
        else log.i("nav $nav sent")
        return sent
    }

    /**
     * Continue the launcher's light bar over the still-black surface. [skipIntro] leaves the bar
     * lit; the first decoded frame stretches it and fades it out. A session that already opened
     * a renderer (home or settings, then back) does not get another one — that re-attach is the
     * `video epoch open` after the picture was already up.
     */
    private fun maybeShowIntro() {
        val adapter = wasidremin.gmccpa.ocbm.AdapterWifi.enabled(this)
        val reattach = if (adapter) wasidremin.gmccpa.ocbm.AdapterSession.videoEpoch > 0 else wiredVideoEpoch > 0
        if (reattach) return
        val v = StartupAnimationView(this)
        v.skipIntro()
        v.setPhoneName(DisplayPrefs.lastPhoneName(this))
        v.setStage(StartupAnimationView.Stage.CARPLAY)
        v.onFinished = {
            v.removeCallbacks(introLate)
            root.removeView(v)
            introGear?.let { root.removeView(it) }
            if (intro === v) intro = null
            introGear = null
        }
        root.addView(v, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        val gear = wasidremin.gmccpa.IntroSettingsButton(this) {
            log.i("intro: settings")
            openSettings()
        }
        root.addView(gear, android.widget.FrameLayout.LayoutParams(dp(72), dp(72), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(20)
            marginEnd = dp(28)
        })
        intro = v
        introGear = gear
        v.postDelayed(introLate, 8_000)
    }

    /** Main thread. HevcRenderer posts here from the `FIRST FRAME RENDERED` line. */
    private fun onIntroFrame() {
        if (isFinishing || isDestroyed) return
        finishIntro(late = false)
    }

    private fun finishIntro(late: Boolean) {
        val v = intro ?: return
        if (introFinished) return
        introFinished = true
        v.removeCallbacks(introLate)
        if (late) log.w("intro: no frame in 8s — finishing")
        v.finish()
    }

    /** Surface and touch only. Audio, metadata, and the mic already belong to [wasidremin.gmccpa.ocbm.AdapterSession]. */
    private fun startAdapterSession() {
        if (generation != null) { log.i("session already started"); return }
        generation = AtomicBoolean(true)
        CarPlaySessionService.start(this)
        log.i("adapter session — picture and sound arrive over USB")
    }

    private fun startSession() {
        if (generation != null) { log.i("session already started"); return }
        val gen = AtomicBoolean(true)
        generation = gen
        videoConnectArmed = false

        // Pin the process to foreground priority so backgrounding (GM dialog / reverse gear / app
        // switch) can't let LMK reclaim the live session. See CarPlaySessionService for the scope limit.
        CarPlaySessionService.start(this)

        // Prime, don't just construct: the AudioTrack is built and playing before the seam even
        // connects, so the first ADTS frame decodes straight into a live track.
        // Pass the AudioManager so media can HOLD AUDIOFOCUS_GAIN — without a resting focus owner the
        // head unit's volume knob stays stuck on Phone/Siri after a transient holder abandons focus.
        val p = AacPlayer(getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager)
        p.start(); p.prime(); player = p
        // The non-media half. Ducking is routed straight at the media track: every other purpose
        // plays at unity. This is the VOICE duck source only; AacPlayer combines it with its own
        // focus-driven duck (min of the two), so an AUDIOFOCUS_GAIN cannot cancel a live prompt's duck.
        voiceRouter = VoiceRouter(
            this,
            onDuck = { ducked -> player?.setVoiceDucked(ducked) },
            // Pausing (not ducking) media is what lets the volume knob reach the voice group while
            // Siri speaks — MUSIC outranks VOICE_COMMAND, and a ducked track still counts as active.
            onAssistant = { speaking -> player?.setAssistantSpeaking(speaking) },
        ).also { it.start() }
        // Connect eagerly: the same socket carries the capture gate, so a data-triggered connect
        // would deadlock (no connection -> no gate -> no capture -> no data).
        micUplink = MicUplink(getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager,
            assistantHeld = { wasidremin.gmccpa.voice.AssistantRole.held(applicationContext) }).also { it.start() }

        // Bind BEFORE returning so a failure is visible and retryable, not swallowed on a thread.
        val vs = bindOrNull(9001) ?: run { failStart("video"); return }
        SessionTrace.Board.up(BOARD_VIDEO, "bound :9001, listening")
        val aus = bindOrNull(9002) ?: run { vs.close(); failStart("audio"); return }
        SessionTrace.Board.up(BOARD_MEDIA, "bound :9002, listening")
        servers.add(vs); servers.add(aus)

        // The video seam is served whether or not a Surface exists, and the connection is HELD either
        // way. With no renderer we drain and DISCARD.
        //
        // Closing instead produced a ~30-60 Hz accept/close storm: `forward_screen2`
        // (`crates/vendor/receiver/src/session.rs`) re-dials on the very next frame with NO backoff —
        // `connect_timeout(2 s)` per call, `Err` returns and the next frame retries; the 2 s is a ceiling
        // on failure, not a delay — and fires a ForceKeyFrame on EVERY successful connect, from the
        // spawned `send_force_key_frame_stream` thread in the same fn. That is one connect plus one
        // encrypted event-channel command per frame,
        // taken on the GLOBAL event mutex that also carries touch, and it makes iOS emit all-IDR at
        // roughly 10x bitrate, degrading audio too. "Don't accept yet" would not have helped: on Linux
        // connect() to a bound listener completes in the kernel without accept().
        //
        // ocbmd — the other consumer of this exact seam — accepts unconditionally and back-pressures or
        // drops FRAMES, never the connection; so does the audio seam three lines below.
        serve(gen, vs, "video") { ins ->
            val r = renderer
            if (r != null) r.consume(ins) else discardUntilRenderer(gen, ins)
        }
        // Each :9002 accept is one stream on this wired path. The adapter path fires
        // onStreamStart from AudioSeam's MARK_FORMAT instead, because that pipe stays open.
        serve(gen, aus, "audio") { ins -> p.onStreamStart(0L); p.consume(ins) }

        // :9003 carries every NON-media audioType — Siri, telephony, alerts, navigation prompts
        // (session.rs routes them there, tagged `[rate u32 BE][ch u16 BE][atype u8][len u32 BE][AU]`).
        // `VoiceRouter.consume` decodes it — a real AAC-ELD decode into USAGE_VOICE_COMMUNICATION /
        // USAGE_ASSISTANT tracks, with the media duck driven from it. Independently of that, the seam
        // MUST be bound: `forward_to_sink` logs its failed connect once per RTP packet with no
        // transition gate, so the first nav prompt of a drive produces ~50 log lines a second,
        // permanently, burying every other diagnostic in the ring buffer.
        //
        // Reading continuously is required, not just decoding — accepting without reading fills the
        // socket buffer and stalls the producer's write for its full 2 s timeout. Best-effort by
        // design: voice is optional, so a bind failure must NOT take video and media audio down with it.
        val vos = bindOrNull(9003)
        if (vos != null) {
            servers.add(vos)
            SessionTrace.Board.up(BOARD_VOICE, "bound :9003, listening")
            serve(gen, vos, "voice") { ins -> voiceRouter?.consume(ins) }
        } else {
            log.e("voice seam :9003 could not be bound — Siri/telephony/nav audio is discarded AND the "
                + "producer will log a failed connect per packet; continuing without it")
            // note(), not failed(): the E above is the severity.
            SessionTrace.Board.note(BOARD_VOICE, "FAILED (bind :9003 refused — Siri/call/nav audio discarded this session)")
        }
        // :9004 carries now-playing metadata and album art as `[u32 BE "META"][u32 BE len][marker]
        // [payload]`. Bound here rather than in CarPlaySessionService because the service is
        // priority-only by its own KDoc — session ownership still lives in this Activity (T2.2 open),
        // and a lone seam with a different owner and a different generation guard is exactly the
        // stale-listener / EADDRINUSE shape the per-generation AtomicBoolean exists to prevent.
        //
        // Best-effort like :9003, but for the opposite reason. An UNBOUND port here is cheap: the
        // producer connects lazily per record and warns once (the `WARNED.swap` block in
        // `crates/vendor/iap2-core/src/metadata.rs`), unlike the voice
        // seam's per-packet log storm. An accepted-but-unread one is the expensive case — the producer
        // writes under a SINK mutex it SHARES with the iAP2 reader, and since the core is in-process
        // that reader is our own thread, so a stalled consumer here stalls iAP2 ingest for the whole
        // session. Bind, and always drain.
        val mts = bindOrNull(9004)
        if (mts != null) {
            servers.add(mts)
            SessionTrace.Board.up(BOARD_META, "bound :9004, listening")
            val seam = MetadataSeam(ProbeLog.sub("meta")) { m, pl ->
                // /command plists are a different plane from now-playing; keep NowPlayingState purely
                // about media rather than teaching it about view areas.
                if (m == MetadataSeam.META_CMD) onCommandPlist(pl) else nowPlaying.dispatch(m, pl)
            }
            serve(gen, mts, "meta") { ins -> seam.consume(gen, ins) }
        } else {
            log.e("metadata seam :9004 could not be bound — the now-playing card stays empty; continuing")
            SessionTrace.Board.note(BOARD_META, "FAILED (bind :9004 refused — now-playing card empty, view-area and modesChanged commands lost this session)")
        }

        log.i("session up: seams listening on :9001 (HEVC), :9002 (AAC-LC), :9003 (voice, routed), "
            + ":9004 (metadata); audio is surface-independent")
    }

    /** Surface-scoped. The codec cannot outlive the Surface it renders into. */
    private fun attachRenderer(holder: SurfaceHolder) {
        if (wasidremin.gmccpa.ocbm.AdapterWifi.enabled(this)) {
            wasidremin.gmccpa.ocbm.AdapterSession.attachVideo(holder.surface) { onIntroFrame() }
            SessionTrace.Board.up(BOARD_RENDERER, "Surface attached — USB HEVC ${VideoFrame.width}x${VideoFrame.height}")
            return
        }
        if (renderer != null) { log.i("renderer already attached"); return }
        wiredVideoEpoch++
        val r = HevcRenderer(VideoFrame.width, VideoFrame.height, holder.surface, { requestKeyframe() }) { onIntroFrame() }
        r.start()
        renderer = r
        // Let the SESSION line SAMPLE the counters at emit rather than depending on HevcRenderer.stop()
        // having pushed them: stop() never runs on `superseded`/`process_death` and races `host_gone`,
        // which is how a session that observed a first frame still reported frames=0. Three AtomicLong
        // reads, once per session end — not a hook on the per-frame path.
        wasidremin.gmccpa.logging.SessionSummary.current()?.avCountersSource =
            { longArrayOf(r.framesRendered.get(), r.ausDropped.get(), r.bytesIn.get()) }
        log.i("renderer attached to Surface")
        SessionTrace.Board.up(BOARD_RENDERER, "Surface attached — decoding ${VideoFrame.width}x${VideoFrame.height} into it")
        // Any seam connection already open belongs to the previous renderer; drop it so the producer
        // re-dials into this one and sends a fresh IDR.
        val bounced = liveSockets.filter { it.localPort == 9001 }
        bounced.forEach { runCatching { it.close() } }   // deliberate: the bounce IS the close
        if (bounced.isNotEmpty()) {
            // A connection WAS live, so the producer is demonstrably dialling and owes us a re-dial —
            // this is the only in-file precondition strong enough to arm on (see EXPECT_VIDEO_CONNECT).
            SessionTrace.expect(EXPECT_VIDEO_CONNECT, VIDEO_REDIAL_MS,
                "the Surface came back and the live :9001 connection was bounced, so forward.rs " +
                "should re-dial on its next frame (a ForceKeyFrame is going out now to produce one)")
        }
        // ASK iOS for the IDR rather than waiting to be given one.
        //
        // A fresh codec cannot render anything until an IDR arrives, so every re-attach — the driver
        // going Home and back, a GM dialog, reverse gear — shows a black screen until then. Owner
        // reported this 2026-09-08 on return from the homescreen. The socket bounce above only makes
        // the PRODUCER re-dial; how soon a keyframe follows is iOS's choice, and in practice it is
        // long enough to look broken.
        //
        // Ordered strictly AFTER the close: the request goes out on the event channel and the IDR
        // comes back down the seam, so the connection that will carry it must be the new one. Request
        // first and the answer can land on a socket we are about to drop.
        //
        // This does NOT keep the decoder alive across the gap — a SurfaceView's Surface dies with the
        // Activity and the codec dies with it. It only shortens the black window to about one round
        // trip. Keeping the decoder running across a background (MediaCodec.setOutputSurface onto a
        // parking Surface) was built and tested on this rig 2026-09-08: the Intel decoder ACCEPTED the
        // swap, but the ImageReader drain was hung on the touch thread, which makes blocking
        // event-channel calls — it stalled the drain, back-pressured the decoder and produced visible
        // video anomalies. Pulled. If it is retried, the drain needs its own thread.
        requestKeyframe()
    }

    private fun detachRenderer() {
        if (wasidremin.gmccpa.ocbm.AdapterWifi.enabled(this)) {
            wasidremin.gmccpa.ocbm.AdapterSession.detachVideo("Surface destroyed")
            SessionTrace.Board.down(BOARD_RENDERER, "Surface destroyed — USB audio stays up")
            return
        }
        val r = renderer ?: return
        renderer = null
        // Drop the sampling closure before stop(); stop()'s own push stays the clean-path value.
        wasidremin.gmccpa.logging.SessionSummary.current()?.avCountersSource = null
        r.stop("Surface destroyed")
        // Unblock the consume thread parked in read(); it releases the codec on its own thread.
        liveSockets.filter { it.localPort == 9001 }.forEach { runCatching { it.close() } }   // deliberate: the unblock IS the close
        log.i("renderer detached — audio and seams stay up")
        SessionTrace.Board.down(BOARD_RENDERER, "Surface destroyed — audio and seams stay up; frames are discarded until a Surface returns")
    }

    /**
     * No Surface: keep the seam connected and throw the frames away.
     *
     * Draining is not optional. Accepting but not reading fills the socket buffer, stalls the producer's
     * `write_two` for the full 2 s SO_SNDTIMEO (`forward_screen2`'s `set_write_timeout`,
     * `crates/vendor/receiver/src/session.rs`), then drops the connection anyway —
     * back-pressuring the screen thread and, through it, the iPhone's screen socket. Reading and
     * discarding costs one read plus a memcpy into a reused buffer.
     *
     * Framing is the seam's own `[u32 BE len][payload]`, read exactly as [HevcRenderer.consume] reads
     * it, so a desync cannot make this allocate — the payload is skipped, never allocated. A 0-length
     * message is legal (`spawn_screen` calls `forward_screen` unconditionally with whatever the
     * conversion produced, `crates/vendor/receiver/src/session.rs`) and is not a desync.
     *
     * Returns as soon as a renderer appears; [attachRenderer] then closes the socket so the producer
     * re-dials into the new renderer with ONE fresh ForceKeyFrame — the designed heal path. If no frames
     * are arriving we are parked in read(), and that same close is what unblocks us.
     */
    private fun discardUntilRenderer(gen: AtomicBoolean, ins: java.io.InputStream) {
        log.i("video seam connected with no Surface — holding open, discarding until one returns")
        SessionTrace.Board.up(BOARD_VIDEO, "connected :9001, no Surface — discarding frames")
        val hdr = ByteArray(4)
        val sink = ByteArray(32 * 1024)
        var msgs = 0L
        var bytes = 0L
        while (gen.get() && renderer == null) {
            if (!readFully(ins, hdr, 4)) break
            val len = ((hdr[0].toInt() and 0xFF) shl 24) or ((hdr[1].toInt() and 0xFF) shl 16) or
                      ((hdr[2].toInt() and 0xFF) shl 8) or (hdr[3].toInt() and 0xFF)
            if (len < 0 || len > MAX_MESSAGE) {
                log.e("implausible seam message length $len — desync, dropping connection")
                break
            }
            if (len > 0 && !skipFully(ins, len, sink)) break
            msgs++; bytes += (len + 4).toLong()
        }
        log.i("discarded $msgs frames ($bytes B) with no Surface")
    }

    // deliberate (both readers): on the discard path a read exception is the close that
    // [attachRenderer] issues to make the producer re-dial — the designed heal, not a fault; the
    // discard summary line and the serve loop's next accept say what happened.
    private fun readFully(ins: java.io.InputStream, dst: ByteArray, n: Int): Boolean {
        var off = 0
        while (off < n) {
            val r = try { ins.read(dst, off, n - off) } catch (e: Exception) { return false }
            if (r <= 0) return false
            off += r
        }
        return true
    }

    /** Skip `n` bytes without allocating them. Socket skip() may short-count or block; read instead. */
    private fun skipFully(ins: java.io.InputStream, n: Int, sink: ByteArray): Boolean {
        var left = n
        while (left > 0) {
            val want = if (left < sink.size) left else sink.size
            val r = try { ins.read(sink, 0, want) } catch (e: Exception) { return false }
            if (r <= 0) return false
            left -= r
        }
        return true
    }

    private fun failStart(which: String) {
        log.e("$which seam could not be bound after $BIND_RETRIES attempts — no A/V; finishing")
        // note(), not failed(): the E above is the severity.
        SessionTrace.Board.note(boardFor(which), "FAILED (bind refused after $BIND_RETRIES attempts — no A/V, screen finishing)")
        stopSession("$which seam bind failed")
        runOnUiThread { finish() }
    }

    /**
     * Bind loopback with SO_REUSEADDR and a bounded retry. We perform the active close on the accepted
     * sockets, so the listener tuple can sit in TIME_WAIT across a quick stop/start; and a stale
     * generation may still hold the port for a moment.
     */
    private fun bindOrNull(port: Int): ServerSocket? {
        repeat(BIND_RETRIES) { attempt ->
            var ss: ServerSocket? = null
            try {
                ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4)
                return ss
            } catch (e: Exception) {
                runCatching { ss?.close() }   // deliberate: discarding a socket whose bind failed
                log.e("bind :$port attempt ${attempt + 1}/$BIND_RETRIES failed: ${e.message}")
                try { Thread.sleep(BIND_BACKOFF_MS) } catch (_: InterruptedException) { return null }
            }
        }
        return null
    }

    private fun serve(gen: AtomicBoolean, srv: ServerSocket, label: String, body: (java.io.InputStream) -> Unit) {
        val port = srv.localPort
        val board = boardFor(label)
        Thread({
            try {
                while (gen.get()) {
                    val s = try { srv.accept() } catch (e: Exception) {
                        if (gen.get()) log.e("$label accept: ${e.message}"); break
                    }
                    liveSockets.add(s)
                    log.i("$label seam connected")
                    SessionTrace.Board.up(board, "connected :$port")
                    if (label == "video") SessionTrace.met(EXPECT_VIDEO_CONNECT)
                    // Catch Throwable, not Exception: an 8 MB per-message allocation can throw
                    // OutOfMemoryError (an Error), which would otherwise escape to the outer finally
                    // and permanently close this listener — a black screen with a live session. Only
                    // this one connection should die; the listener must keep accepting.
                    try { body(s.getInputStream()) } catch (e: Throwable) { log.e("$label: ${e.javaClass.simpleName}: ${e.message}") }
                    finally {
                        liveSockets.remove(s); runCatching { s.close() }   // deliberate: teardown
                        if (gen.get()) SessionTrace.Board.up(board, "bound :$port, listening (producer disconnected)")
                    }
                }
            } finally {
                runCatching { srv.close() }   // deliberate: never leave a bound socket behind
            }
        }, "cp-$label").apply { isDaemon = true }.start()
    }

    /**
     * The single teardown choke point — [failStart], [onDestroy] and [endSession] all come through
     * here — and therefore the owner of the `av/` expectation scope: every expectation the A/V
     * components armed is moot once the AirPlay session is gone, and left armed each would fire as an
     * `EXPECTED-MISSING` a few seconds after a phone that simply walked away. Scoped, not
     * [SessionTrace.cancelAll]: the phone leaving does not end the OCBM link, and a box-side
     * expectation in flight at that moment must survive.
     *
     * [why] is threaded into every component's `stop` and every board entry, so a capture answers
     * "was this teardown expected" from the lines themselves.
     */
    private fun stopSession(why: String) {
        if (wasidremin.gmccpa.ocbm.AdapterWifi.enabled(this) && generation != null) {
            generation = null
            wasidremin.gmccpa.ocbm.AdapterSession.detachVideo(why)
            CarPlaySessionService.stop(this)
            nowPlaying.clear("adapter screen stopped")
            SessionTrace.Board.down(BOARD_RENDERER, "session stopped — $why")
            log.i("adapter screen stopped — $why")
            return
        }
        val gen = generation ?: return
        generation = null
        gen.set(false)
        renderer?.stop(why); renderer = null
        player?.stop(why)
        // Flag-only, like the player: VoiceRouter releases its codecs/tracks on the consume thread.
        // Skipping this strands up to four AudioTracks AND their focus requests, and an unabandoned
        // USAGE_VOICE_COMMUNICATION request keeps the hardware volume keys pinned to the call group
        // for the rest of the session.
        voiceRouter?.stop(why)
        micUplink?.stop(why)
        // Closing the accepted socket is the only reliable way to unblock a consumer parked in read().
        liveSockets.forEach { runCatching { it.close() } }; liveSockets.clear()   // deliberate: teardown
        servers.forEach { runCatching { it.close() } }; servers.clear()           // deliberate: teardown
        player = null
        voiceRouter = null
        micUplink = null
        CarPlaySessionService.stop(this)
        // Clear AND publish the cleared picture: a card still showing the last track over a dead
        // session is the metadata twin of the frozen-frame bug onSessionEnded exists to prevent.
        nowPlaying.clear("session stopped")
        for (b in listOf(BOARD_VIDEO, BOARD_MEDIA, BOARD_VOICE, BOARD_META)) SessionTrace.Board.down(b, "session stopped — $why")
        SessionTrace.Board.down(BOARD_RENDERER, "session stopped — $why")
        // After the components' own stop(): those that could cancel synchronously have, with a more
        // specific reason; what remains is on threads still unwinding, and the session reason is
        // the honest one for it. A later per-name cancel from those threads is then a silent no-op.
        SessionTrace.cancelScope("av/", "A/V session stopped — $why")
        log.i("session stopped — $why")
    }

    /**
     * Ask iOS for a fresh IDR. Off the UI thread — it rides the same blocking event channel.
     *
     * The result used to be discarded. A refused ForceKeyFrame is a dead event channel under a
     * black screen — the one symptom that has no other line. Once per run of refusals, not per
     * request: [HevcRenderer.requestKeyframe] re-issues every 500 ms while non-IRAP AUs arrive.
     */
    private fun requestKeyframe() {
        if (wasidremin.gmccpa.ocbm.AdapterWifi.enabled(this)) {
            wasidremin.gmccpa.ocbm.AdapterSession.requestKeyframe()
            return
        }
        touchHandler?.post {
            if (NativeCore.forceKeyFrame()) {
                keyframeReqFailed = false
            } else if (!keyframeReqFailed) {
                keyframeReqFailed = true
                log.w("ForceKeyFrame refused by the event channel — no IDR is coming; the screen stays black until it recovers")
            }
        }
    }

    /**
     * An inbound `/command` plist off the `:9004` seam.
     *
     * Two commands are acted on. `requestViewArea` lays the surface out. `modesChanged` is decoded
     * for its Speech app state and handed to [VoiceRouter.onModes] (A11): iOS states "Siri is up /
     * Siri is done" on this channel ~400 ms before the first voice AU and ~3.3 s before the energy
     * gate can infer the end. This seam is best-effort — `emit_command_plist` try_locks and drops
     * under a now-playing or artwork write — so the router treats it as a hint over its energy gate,
     * never as the only truth. Everything else (`setNightMode`, `duckAudio`, …) is ignored rather than
     * half-handled; `duckAudio` in particular did not arrive once in the 2026-09-09 session.
     *
     * Runs on the `cp-meta` seam thread and must not block: volatile writes into the router, and the
     * layout change posted to the UI thread.
     */
    private fun onCommandPlist(payload: ByteArray) {
        val root = BPlist.parse(payload) ?: return
        val type = BPlist.str(root, "type")
        if (!videoConnectArmed && generation?.get() == true) {
            // First /command of this generation: iOS is past RECORD, so the screen stream is owed.
            // Armed only if no video connection is already live (a re-attach mid-session may have
            // beaten this plist); once per generation either way. See EXPECT_VIDEO_CONNECT.
            videoConnectArmed = true
            if (liveSockets.none { it.localPort == 9001 }) {
                SessionTrace.expect(EXPECT_VIDEO_CONNECT, VIDEO_CONNECT_MS,
                    "iOS sent its first /command plist (type=$type) on the event channel accepted at " +
                    "RECORD, so it should SETUP the screen stream and forward.rs should dial :9001 " +
                    "(730ms after RECORD on 2026-09-09)")
            }
        }
        when (type) {
            "modesChanged" -> onModesChanged(root, payload.size)
            "requestUI" -> runOnUiThread { openSettings() }
            "requestViewArea" -> {
                val idx = BPlist.int(root, "params", "viewAreaIndex")?.toInt() ?: return
                if (idx !in VIEW_AREAS.indices) {
                    log.w("requestViewArea index=$idx outside the ${VIEW_AREAS.size} declared areas — ignoring")
                    return
                }
                log.i("requestViewArea index=$idx -> laying the surface out at ${declaredViewArea(idx)}")
                runOnUiThread { applyViewArea(idx) }
            }
        }
    }

    /**
     * Decode `params.appStates[]{appStateID, entity, speechMode}` out of a `modesChanged`.
     *
     * The shape is byte-proven, not documented: every frame in
     * `docs/ops/captures/2026-07-24_carplay_cmd_capture.bin` carries exactly this plus
     * `resources[]{resourceID, entity, permanentEntity}`, and appStateID 1 is the one entry that
     * carries `speechMode` — Apple's Speech app state. `-1` is NotApplicable; in Apple's writer it is
     * the frame's only 8-byte integer, and its departure is the whole of the 287 B → 277 B bracket
     * around the Siri turn in the 2026-09-09 capture. appStateID 2 is PhoneCall: car-verified
     * 2026-10-27, its entity was non-zero for exactly the two calls of that drive, and it sets
     * CALL's idle clock. appStateID 3 is believed to be TurnByTurn (unverified) and is passed for
     * logging only. `BPlist` decodes arrays to `List` and dicts to `Map<String, Any?>` with `Long`
     * integers, which is what this destructures; an unexpected shape yields `-1`, i.e. "not
     * applicable", i.e. today's energy-gate behaviour.
     */
    @Suppress("UNCHECKED_CAST")
    private fun onModesChanged(root: Any?, size: Int) {
        val params = (root as? Map<String, Any?>)?.get("params") as? Map<String, Any?> ?: return
        val states = params["appStates"] as? List<Any?> ?: return
        var speechMode = -1L; var speechEntity = 0L; var phoneEntity = 0L; var turnsEntity = 0L
        for (s in states) {
            val d = s as? Map<String, Any?> ?: continue
            val id = d["appStateID"] as? Long ?: continue
            val ent = d["entity"] as? Long ?: 0L
            when (id) {
                1L -> { speechEntity = ent; speechMode = d["speechMode"] as? Long ?: -1L }
                2L -> phoneEntity = ent
                3L -> turnsEntity = ent
            }
        }
        voiceRouter?.onModes(speechMode, speechEntity, phoneEntity, turnsEntity, size)
    }

    /**
     * Move the visible picture to a declared view area by CROPPING on the hardware composer.
     *
     * ## Why a crop, and why not a layout change
     * iOS keeps encoding the FULL panel and draws its UI into the view-area sub-rect, filling the
     * rest of the frame with black — device-observed 2026-09-08 (130 parameter-set bursts, identical
     * CSD, zero decoder reconfigurations), matching upstream's "255 rect updates, coded size
     * constant". So the buffer is always 2400x960 with content at, say, 1416x842@(188,118).
     *
     * Shrinking the SurfaceView's LAYOUT would make SurfaceFlinger scale that whole frame into the
     * smaller window — the UI would appear at 59% and mispositioned. The correct operation is a crop:
     * show only the sub-rect, 1:1, at its own origin.
     *
     * ## Why this stays on the overlay
     * A SurfaceView cannot crop its buffer through the View API, but the hardware composer is already
     * doing exactly this per layer — the baseline dump shows `sourceCrop` and `displayFrame` as
     * separate rects with `composition=DEVICE (2)` and `usesClientComposition=false`, i.e. the whole
     * display scans out with no GPU. [SurfaceControl.Transaction.setGeometry] exposes those two rects
     * to the app (API 31; this unit is 32), so the crop costs nothing: no GPU composition, no extra
     * copy, no added latency. A TextureView would achieve the same picture by moving 2400x960 60 fps
     * HEVC onto GPU composition, which is the cost this avoids.
     *
     * If HWC cannot satisfy the crop it silently falls back, and that is directly observable in the
     * same dump as `forceClientComposition=true` — so this verifies itself rather than needing trust.
     *
     * ## Re-applying
     * The view system owns this SurfaceControl and resets its geometry on relayout, so the current
     * area is re-applied from `surfaceChanged` as well as from here.
     */
    private fun applyViewArea(index: Int) {
        if (index !in VIEW_AREAS.indices) return
        val from = declaredViewArea(viewAreaIndex)
        val to = declaredViewArea(index)
        viewAreaIndex = index
        log.i("view area [$index] $to — cropping on the compositor over ${VIEW_AREA_ANIM_MS}ms")
        if (from == to) { applyGeometry(to); return }

        // ASYMMETRIC, and not interpolated. We cannot see iOS's per-step rect — the screen header
        // that carries it is consumed in-process before the seam — so any ramp of ours runs on a
        // different clock from its animation. Device-observed 2026-09-08: an interpolated ramp looked
        // right growing and wrong shrinking, because on the way down our crop LEADS iOS and its
        // animation then plays out inside an already-clipped window.
        //
        // Both directions are correct if the crop is never smaller than iOS's current content:
        //   GROW   apply the target at once. The extra frame area we reveal is iOS's own black until
        //          its UI expands into it — which is exactly what was on screen before.
        //   SHRINK wait out the animation, then crop. For those 3 s the surround is iOS's black
        //          rather than GM's chrome, and it snaps at the end; clipping the animation is worse.
        //
        // Generation-guarded so a second request during the delay cancels the pending one rather
        // than cropping to a stale area after it.
        // Bars move WITH the crop, same asymmetry and for the same reason.
        //   GROW   hide the bars and reveal the frame together; the picture must never expand under
        //          a bar that is still on screen.
        //   SHRINK stay immersive for the whole animation (iOS's own black surrounds its shrinking
        //          UI), then crop and reveal GM's chrome as one event at the end.
        // Safe to schedule blind because the duration is OURS: the receiver sends iOS
        // animationDurationMillis=3000 in updateViewArea.
        val gen = ++viewAreaGen
        val growing = to.width() * to.height() >= from.width() * from.height()
        if (growing) {
            applySystemUi()
            applyGeometry(to)
        } else {
            surfaceView.postDelayed({
                if (gen != viewAreaGen) return@postDelayed
                applyGeometry(to)
                applySystemUi()
            }, VIEW_AREA_ANIM_MS)
        }
    }

    /**
     * Crop the layer to [r] and place it at the same rect — 1:1, no scaling, since the view area is
     * expressed in the same panel coordinates as the buffer.
     *
     * Guarded on API 31 for [SurfaceControl.Transaction.setGeometry]; below that the picture simply
     * stays full-bleed, which is the behaviour this app shipped with.
     */
    private fun applyGeometry(r: android.graphics.Rect) {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        val sc = surfaceView.surfaceControl
        if (sc == null || !sc.isValid) return
        runCatching {
            android.view.SurfaceControl.Transaction().use { t ->
                t.setGeometry(sc, r, r, android.view.Surface.ROTATION_0)
                t.apply()
            }
        }.onFailure { log.w("setGeometry $r failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /**
     * Touch → HID report to the iPhone, dispatched off the UI thread.
     *
     * A send takes the event-channel lock and can block on a stalled socket write for seconds; doing
     * that inline from `onTouch` risks an ANR. MOVEs are coalesced latest-wins (a stale MOVE has no
     * value once a newer one exists) while DOWN and UP are never dropped and keep their order.
     *
     * Single-touch: only the primary pointer is tracked. Without that, a second finger lifting sends
     * UP while the first is still down, and iOS sees the gesture end mid-drag.
     */
    private fun onTouch(v: View, ev: MotionEvent): Boolean {
        if (previewOnly) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_UP) {
                log.i("topbar: preview video touch n=(%.3f, %.3f) — no command sent".format(
                    ev.x / v.width.coerceAtLeast(1), ev.y / v.height.coerceAtLeast(1)))
            }
            return true
        }
        if (v.width <= 0 || v.height <= 0) return false
        val action = ev.actionMasked
        val pid = ev.getPointerId(ev.actionIndex)

        val phase = when (action) {
            MotionEvent.ACTION_DOWN -> { primaryPointerId = pid; 0 }
            MotionEvent.ACTION_MOVE -> 1
            MotionEvent.ACTION_UP -> { primaryPointerId = -1; 2 }
            MotionEvent.ACTION_CANCEL -> { primaryPointerId = -1; PHASE_CANCEL }
            MotionEvent.ACTION_POINTER_DOWN -> return true               // a non-primary finger arrived
            // If the PRIMARY finger lifts first, send UP (don't let MOVEs teleport to the survivor);
            // a non-primary finger lifting is ignored.
            MotionEvent.ACTION_POINTER_UP -> if (pid == primaryPointerId) { primaryPointerId = -1; 2 } else return true
            else -> return false
        }
        // For a POINTER_UP the lifting pointer's own position is at actionIndex; otherwise track the
        // primary pointer (falling back to index 0 once it has been cleared).
        val idx = if (action == MotionEvent.ACTION_POINTER_UP) ev.actionIndex
                  else if (primaryPointerId >= 0) ev.findPointerIndex(primaryPointerId).takeIf { it >= 0 } ?: 0
                  else 0
        // Two coordinate spaces, and they must not be mixed.
        //
        // Sidebar resize: the phone was told the display IS the video rectangle (origin at the
        // surface's left edge). A tap at the surface's left edge is x=0.
        //
        // View area / full panel: the phone was told the display is the whole 2400x960 panel, and a
        // view area is a sub-rect inside it. `nativeTouch` scales by DISPLAY_W/H. Using the view's
        // own width there would report the video's left edge as x=0 instead of the view area's origin.
        val framed = viewAreaIndex == 0
        val nx = (if (framed) ev.getX(idx) / v.width.toFloat()
            else (v.left + ev.getX(idx)) / DISPLAY_W.toFloat()).coerceIn(0f, 1f)
        val ny = (if (framed) ev.getY(idx) / v.height.toFloat()
            else (v.top + ev.getY(idx)) / DISPLAY_H.toFloat()).coerceIn(0f, 1f)

        if (phase == 1) {
            // Latest-wins, lost-wakeup-free: set the pending MOVE and post a drain that atomically
            // takes it. Extra posts are cheap and self-coalesce (a later drain finds null).
            pendingMove.set(Triple(1, nx, ny))
            touchHandler?.post { pendingMove.getAndSet(null)?.let { (p, x, y) -> send(p, x, y) } }
        } else if (phase == PHASE_CANCEL) {
            // The HID report is [buttons][x][y] and the JNI maps phase 2 to a tip-up: there is no cancel
            // semantic on the wire, so folding CANCEL into the UP arm delivered an ABORTED gesture to
            // iOS as a completed lift — a phantom tap at the last coordinates. iOS's tap recogniser
            // instead FAILS once the touch moves past its allowable movement, so displace beyond tap
            // slop and lift there. Worst case is a few pixels of scroll, which is strictly better.
            // Both go through touchHandler so they keep their order behind any in-flight MOVE.
            pendingMove.set(null)
            val cx = (if (nx + CANCEL_SLOP_N <= 1f) nx + CANCEL_SLOP_N else nx - CANCEL_SLOP_N)
                .coerceIn(0f, 1f)
            touchHandler?.post {
                log.i("touch CANCEL→displaced UP")
                send(1, cx, ny)
                send(2, cx, ny)
            }
        } else {
            pendingMove.set(null)
            touchHandler?.post { send(phase, nx, ny) }
        }
        if (phase == 0) v.performClick()
        return true
    }

    private fun send(phase: Int, nx: Float, ny: Float) {
        if (wasidremin.gmccpa.ocbm.AdapterWifi.enabled(this)) {
            val sent = wasidremin.gmccpa.ocbm.AdapterSession.sendTouch(phase.toByte(), nx, ny)
            if (!sent) log.w("touch ${phaseName(phase)} sent=false — adapter has no subscription")
            else if (phase != 1) log.i("touch ${phaseName(phase)} n=(%.3f, %.3f) sent=true".format(nx, ny))
            return
        }
        val tw = if ((VideoFrame.railPx > 0 || topStripPx > 0) && viewAreaIndex == 0) VideoFrame.width else DISPLAY_W
        val th = if ((VideoFrame.railPx > 0 || topStripPx > 0) && viewAreaIndex == 0) VideoFrame.height else DISPLAY_H
        val sent = NativeCore.touch(phase, nx, ny, tw, th)
        // Log every DOWN/UP and every failure — a dying event channel is otherwise invisible mid-drag.
        // A refused send is a W: it IS the dying event channel, and at I it was indistinguishable
        // from a healthy tap in a grep for problems.
        if (!sent) {
            log.w("touch ${phaseName(phase)} n=(%.3f, %.3f) sent=false — the event channel refused it".format(nx, ny))
        } else if (phase != 1) {
            log.i("touch ${phaseName(phase)} n=(%.3f, %.3f) sent=true".format(nx, ny))
        }
    }

    private fun phaseName(p: Int) = when (p) { 0 -> "down"; 1 -> "move"; else -> "up" }

    override fun onResume() {
        super.onResume()
        applySystemUi()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemUi()
    }

    override fun onDestroy() {
        VideoPresentation.onSizeChanged = null
        topBarData?.stop(); topBarData = null
        intro?.removeCallbacks(introLate)
        intro = null
        introGear = null
        // Only clear the shared handle if it still points at US. A newer generation may already have
        // published itself (launch of the replacement can precede this teardown), and clearing it
        // unconditionally would leave the LIVE screen unreachable from [onSessionEnded].
        if (live?.get() === this) {
            nowPlaying.onCallChanged = null
            live = null
        }
        stopSession("activity destroyed")
        touchThread?.quitSafely(); touchThread = null; touchHandler = null
        super.onDestroy()
    }

    /**
     * Stop the A/V session and take the screen down. See [onSessionEnded] for why this finishes.
     *
     * Idempotent: `stopSession` returns immediately once `generation` is null, and `finish()` on an
     * already-finishing Activity is a no-op.
     */
    private fun endSession(why: String) {
        wiredVideoEpoch = 0
        log.i("CarPlay session ended ($why) — stopping A/V and closing the screen")
        stopSession(why)
        runOnUiThread { if (!isFinishing) finish() }
    }
}

/** A drawn gear, so the way back to settings does not depend on an emoji font or a resource. */
private class GearButton(ctx: android.content.Context, private val onTap: () -> Unit) : View(ctx) {
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC171B1E.toInt()
        style = Paint.Style.FILL
    }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF2F4F5.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    init {
        contentDescription = "Settings"
        isClickable = true
        setOnClickListener { onTap() }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = minOf(width, height) * 0.46f
        ink.strokeWidth = outer * 0.12f
        canvas.drawCircle(cx, cy, outer, disc)
        canvas.drawCircle(cx, cy, outer * 0.38f, ink)
        canvas.drawCircle(cx, cy, outer * 0.72f, ink)
        val teeth = 8
        for (i in 0 until teeth) {
            val a = Math.toRadians(i * (360.0 / teeth) - 90.0)
            val c = Math.cos(a).toFloat()
            val s = Math.sin(a).toFloat()
            canvas.drawLine(cx + c * outer * 0.62f, cy + s * outer * 0.62f, cx + c * outer * 0.95f, cy + s * outer * 0.95f, ink)
        }
    }
}

/** Same three tones as the other app's `SidebarButtonTone`, in that app's dark palette. */
private enum class RailTone(val container: Int, val ink: Int) {
    Default(0xFF324B4F.toInt(), 0xFFCCE7EA.toInt()),
    Highlight(0xFF004E5C.toInt(), 0xFFB5EEFF.toInt()),
    Alert(0xFF93000A.toInt(), 0xFFFFDAD6.toInt()),
}

/** The other app's rail icons: Home, Settings, RestartAlt, KeyboardVoice, SkipPrevious, Play, Pause, SkipNext, Call, CallEnd. */
private enum class RailIcon { HOME, SETTINGS, RESET, VOICE, PREV, PLAY, PAUSE, NEXT, PHONE, BACK }

/**
 * 72 dp circular tonal button with a 30 dp drawn icon. This app has no AndroidX, so the Material
 * glyphs are drawn here at the same sizes as `SidebarActionButton`.
 */
private class RailButton(
    ctx: android.content.Context,
    initial: RailIcon,
    description: String,
    private val tone: RailTone,
    // When true and taller than it is wide, the button draws as a vertical capsule (stadium) that
    // fills the space it is given, instead of a disc — used for the mic so it grows into the rail's
    // empty middle. The glyph stays centred and sized from the width.
    private val capsule: Boolean = false,
    private val onTap: (RailButton) -> Unit,
) : View(ctx) {
    var icon: RailIcon = initial
        set(value) {
            field = value
            invalidate()
        }

    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        contentDescription = description
        isClickable = true
        isFocusable = true
        background = null
        setOnClickListener { if (isEnabled) onTap(this) }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        disc.color = tone.container
        disc.alpha = when {
            !isEnabled -> 90
            isPressed -> 200
            else -> 255
        }
        if (capsule && height > width) {
            val cr = width / 2f
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), cr, cr, disc)
        } else {
            canvas.drawCircle(cx, cy, minOf(width, height) / 2f, disc)
        }
        ink.color = tone.ink
        ink.alpha = if (isEnabled) 255 else 120
        // A capsule's glyph is sized from the WIDTH (a little larger than a disc's) so it keeps a
        // confident presence however tall the capsule grows; a disc stays on its smaller dimension.
        val iconPx = if (capsule) width * (40f / 72f) else minOf(width, height) * (30f / 72f)
        canvas.save()
        canvas.translate(cx - iconPx / 2f, cy - iconPx / 2f)
        canvas.scale(iconPx / 24f, iconPx / 24f)
        drawRailIcon(canvas, icon, ink)
        canvas.restore()
    }
}

private fun drawRailIcon(canvas: Canvas, icon: RailIcon, ink: Paint) {
    val style = ink.style
    val width = ink.strokeWidth
    when (icon) {
        RailIcon.HOME -> canvas.drawPath(path {
            moveTo(12f, 3f); lineTo(2.5f, 11.5f); lineTo(5.2f, 11.5f); lineTo(5.2f, 20.5f)
            lineTo(10f, 20.5f); lineTo(10f, 14f); lineTo(14f, 14f); lineTo(14f, 20.5f)
            lineTo(18.8f, 20.5f); lineTo(18.8f, 11.5f); lineTo(21.5f, 11.5f); close()
        }, ink)
        RailIcon.SETTINGS -> drawGear(canvas, ink)
        RailIcon.RESET -> drawReset(canvas, ink)
        RailIcon.VOICE -> drawMic(canvas, ink)
        RailIcon.PREV -> {
            canvas.drawRoundRect(RectF(4.5f, 5f, 7.5f, 19f), 0.8f, 0.8f, ink)
            canvas.drawPath(path { moveTo(18.8f, 5f); lineTo(9.2f, 12f); lineTo(18.8f, 19f); close() }, ink)
        }
        RailIcon.PLAY -> canvas.drawPath(path { moveTo(8f, 4.5f); lineTo(8f, 19.5f); lineTo(19.5f, 12f); close() }, ink)
        RailIcon.PAUSE -> {
            canvas.drawRoundRect(RectF(5.5f, 4.5f, 10f, 19.5f), 1f, 1f, ink)
            canvas.drawRoundRect(RectF(14f, 4.5f, 18.5f, 19.5f), 1f, 1f, ink)
        }
        RailIcon.NEXT -> {
            canvas.drawPath(path { moveTo(5.2f, 5f); lineTo(14.8f, 12f); lineTo(5.2f, 19f); close() }, ink)
            canvas.drawRoundRect(RectF(16.5f, 5f, 19.5f, 19f), 0.8f, 0.8f, ink)
        }
        RailIcon.PHONE -> canvas.drawPath(phonePath(), ink)
        RailIcon.BACK -> {
            canvas.save()
            canvas.rotate(135f, 12f, 12f)
            canvas.drawPath(phonePath(), ink)
            canvas.restore()
        }
    }
    ink.style = style
    ink.strokeWidth = width
}

private fun path(block: Path.() -> Unit) = Path().apply(block)

private fun drawGear(canvas: Canvas, ink: Paint) {
    val body = Path().apply { addCircle(12f, 12f, 5f, Path.Direction.CW) }
    for (i in 0 until 8) {
        val tooth = Path().apply {
            addRoundRect(RectF(10.7f, 1.5f, 13.3f, 7.2f), 1.1f, 1.1f, Path.Direction.CW)
        }
        tooth.transform(android.graphics.Matrix().apply { setRotate(i * 45f, 12f, 12f) })
        body.addPath(tooth)
    }
    body.op(Path().apply { addCircle(12f, 12f, 2.8f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
    canvas.drawPath(body, ink)
}

private fun drawReset(canvas: Canvas, ink: Paint) {
    ink.style = Paint.Style.STROKE
    ink.strokeWidth = 2.2f
    canvas.drawArc(RectF(5f, 5.5f, 19f, 19.5f), -50f, 300f, false, ink)
    ink.style = Paint.Style.FILL
    canvas.drawPath(path { moveTo(16.2f, 3.2f); lineTo(19.6f, 8.4f); lineTo(13.6f, 8.2f); close() }, ink)
}

private fun drawMic(canvas: Canvas, ink: Paint) {
    canvas.drawRoundRect(RectF(9f, 2f, 15f, 12.2f), 3f, 3f, ink)
    ink.style = Paint.Style.STROKE
    ink.strokeWidth = 1.7f
    canvas.drawArc(RectF(6.2f, 6.5f, 17.8f, 16.2f), 0f, 180f, false, ink)
    canvas.drawLine(12f, 16.2f, 12f, 20.4f, ink)
    canvas.drawLine(8.2f, 20.4f, 15.8f, 20.4f, ink)
    ink.style = Paint.Style.FILL
}

/** Material `Call`, in a 24-unit box. `CallEnd` is this path rotated 135°. */
private fun phonePath() = path {
    moveTo(6.62f, 10.79f)
    cubicTo(8.06f, 13.62f, 10.38f, 15.93f, 13.21f, 17.38f)
    lineTo(15.41f, 15.18f)
    cubicTo(15.68f, 14.91f, 16.08f, 14.82f, 16.43f, 14.94f)
    cubicTo(17.55f, 15.31f, 18.76f, 15.51f, 20f, 15.51f)
    cubicTo(20.55f, 15.51f, 21f, 15.96f, 21f, 16.51f)
    lineTo(21f, 20f)
    cubicTo(21f, 20.55f, 20.55f, 21f, 20f, 21f)
    cubicTo(10.61f, 21f, 3f, 13.39f, 3f, 4f)
    cubicTo(3f, 3.45f, 3.45f, 3f, 4f, 3f)
    lineTo(7.5f, 3f)
    cubicTo(8.05f, 3f, 8.5f, 3.45f, 8.5f, 4f)
    cubicTo(8.5f, 5.25f, 8.7f, 6.45f, 9.07f, 7.57f)
    cubicTo(9.18f, 7.92f, 9.1f, 8.31f, 8.82f, 8.59f)
    lineTo(6.62f, 10.79f)
    close()
}
