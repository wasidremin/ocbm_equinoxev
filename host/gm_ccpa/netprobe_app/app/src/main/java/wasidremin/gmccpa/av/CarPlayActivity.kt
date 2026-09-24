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
import wasidremin.gmccpa.VideoFrame
import wasidremin.gmccpa.logging.SessionTrace
import wasidremin.gmccpa.pair.NativeCore
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
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

    companion object {
        /**
         * The live screen, so a session ending elsewhere can tear it down.
         *
         * A WEAK reference on purpose: this is a static field holding an Activity, and a strong one
         * would pin a finished Activity (and its Surface, decoder and window) for the life of the
         * process. Cleared in [onDestroy] too — the weak ref is the backstop, not the plan.
         */
        @Volatile private var live: java.lang.ref.WeakReference<CarPlayActivity>? = null

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
            if (act != null) act.endSession(why) else nowPlaying.clear()
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
            onMetadataChanged = { CarPlayMediaBrowserService.publish(it) }
            onPlaybackTick = { CarPlayMediaBrowserService.publishPlaybackState(it) }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        live = java.lang.ref.WeakReference(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surfaceView = SurfaceView(this)
        // The surface is no longer the content view: it is a POSITIONED child of a panel-sized root,
        // so it can occupy a view area smaller than the panel and let GM's own chrome show around it.
        // The root stays full-bleed (2400x960) and is the coordinate space touch is normalised in —
        // see [onTouch]. Its background is what shows outside the active view area.
        root = android.widget.FrameLayout(this)
        root.addView(surfaceView, android.widget.FrameLayout.LayoutParams(VideoFrame.width, VideoFrame.height, Gravity.TOP or Gravity.START).apply {
            marginStart = VideoFrame.railPx
        })
        surfaceView.holder.setFixedSize(VideoFrame.width, VideoFrame.height)
        setContentView(root)
        applySystemUi()

        touchThread = HandlerThread("cp-touch").also { it.start(); touchHandler = Handler(it.looper) }

        // Seams + audio start HERE, not on surfaceCreated: they must outlive the Surface. Anything
        // that backgrounds this Activity (a GM dialog, reverse gear, a stray `am start`) destroys the
        // Surface, and if the sockets went with it the CarPlay session would be gone for good.
        if (wasidremin.gmccpa.ocbm.AdapterWifi.enabled(this)) startAdapterSession() else startSession()

        surfaceView.setOnTouchListener { v, ev -> onTouch(v, ev) }
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { attachRenderer(h) }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {
                log.i("surface ${w}x$ht (advertised ${VideoFrame.width}x${VideoFrame.height}, rail ${VideoFrame.railPx})")
                // The view system owns this SurfaceControl and resets its geometry on relayout, so a
                // non-default view area has to be re-asserted here or the picture silently goes
                // full-bleed again after any layout pass.
                if (viewAreaIndex != 0) applyGeometry(VIEW_AREAS[viewAreaIndex])
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
        if (viewAreaIndex != 0) ScreenMode.SYSTEM_UI else DisplayPrefs.mode(this)

    private fun applySystemUi() {
        val mode = effectiveMode()
        val hideStatus = mode == ScreenMode.FULLSCREEN || mode == ScreenMode.STATUS_HIDDEN
        val hideNav = mode == ScreenMode.FULLSCREEN || mode == ScreenMode.NAV_HIDDEN
        // FLAG_FULLSCREEN is what hides GM's top bar on the Silverado; the hide-navigation bit is
        // what brings the left bar back when cleared. Both stay, and the insets controller below
        // is what API 34 (the Equinox) actually honours — the legacy flags alone left the car's
        // bars on screen over a 2400x960 picture.
        if (hideStatus) window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        @Suppress("DEPRECATION")
        var vis =
            // ALWAYS: keeps the root at (0,0) 2400x960 so the surface and the crop stay in panel space.
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (hideStatus || hideNav) vis = vis or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        if (hideStatus) vis = vis or View.SYSTEM_UI_FLAG_FULLSCREEN
        if (hideNav) vis = vis or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = vis
        if (Build.VERSION.SDK_INT >= 30) {
            // Never let the decor inset the content. A fit-to-bars layout is what displaced the
            // picture on 2026-09-08. Bar visibility is a separate question from window geometry.
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { c ->
                val status = WindowInsets.Type.statusBars()
                val nav = WindowInsets.Type.navigationBars()
                if (hideStatus) c.hide(status) else c.show(status)
                if (hideNav) c.hide(nav) else c.show(nav)
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
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
        if (chromeIsRail == rail && chrome != null) return
        chromeIsRail = rail
        chrome?.let { root.removeView(it) }
        val view = if (rail) buildRail() else buildGear()
        chrome = view
        view.elevation = dp(8).toFloat()
        if (rail) {
            val railW = if (resized) VideoFrame.railPx else dp(VideoFrame.RAIL_DP)
            root.addView(view, android.widget.FrameLayout.LayoutParams(railW, android.widget.FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START))
        } else {
            // Top-left, same corner as the other app's Settings button. Top-right is where CarPlay
            // draws Home / Work / Now Playing, and a control there covers those cards.
            val lp = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            )
            lp.topMargin = dp(16)
            lp.marginStart = dp(16)
            root.addView(view, lp)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    /** Place the surface 1:1 in the rectangle the phone was told to fill. Never stretch it. */
    private fun applyVideoFrame() {
        val lp = surfaceView.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        val rail = VideoFrame.railPx > 0 && viewAreaIndex == 0
        val w = if (rail) VideoFrame.width else DISPLAY_W
        val h = if (rail) VideoFrame.height else DISPLAY_H
        // Flush against the rail. Gravity END left a black column between the rail and the picture
        // whenever the window was wider than 2400. Leftover pixels, if any, sit on the right.
        val gravity = Gravity.TOP or Gravity.START
        val inset = if (rail) VideoFrame.railPx else 0
        if (lp.width == w && lp.height == h && lp.gravity == gravity && lp.marginStart == inset) return
        lp.width = w
        lp.height = h
        lp.gravity = gravity
        lp.marginStart = inset
        surfaceView.layoutParams = lp
        surfaceView.holder.setFixedSize(w, h)
        log.i("video frame ${w}x$h rail ${if (w == DISPLAY_W) 0 else VideoFrame.railPx}")
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
        val gap = dp(12)
        fun add(parent: LinearLayout, child: View, first: Boolean) {
            parent.addView(child, LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                if (!first) topMargin = gap
                gravity = Gravity.CENTER_HORIZONTAL
            })
        }
        add(top, railButton(RailIcon.HOME, "AAOS Home", RailTone.Default) { _ -> openHome() }, true)
        add(top, railButton(RailIcon.SETTINGS, "Settings", RailTone.Default) { _ -> openSettings() }, false)
        add(top, railButton(RailIcon.RESET, "Reset Device", RailTone.Alert) { reset ->
            reset.isEnabled = false
            requestSessionRestart()
        }, false)
        add(bottom, railButton(RailIcon.VOICE, "Voice Assistant", RailTone.Default) { _ -> sendSiri() }, true)
        add(bottom, railButton(RailIcon.PREV, "Previous", RailTone.Default) { _ ->
            sendMedia(wasidremin.gmccpa.ocbm.Ocbm.MEDIA_BTN_PREV)
        }, false)
        val play = railButton(RailIcon.PLAY, "Play", RailTone.Highlight) { button ->
            val wasPlaying = railAssumedPlaying
            railAssumedPlaying = !wasPlaying
            button.icon = if (railAssumedPlaying) RailIcon.PAUSE else RailIcon.PLAY
            button.contentDescription = if (railAssumedPlaying) "Pause" else "Play"
            sendMedia(if (wasPlaying) wasidremin.gmccpa.ocbm.Ocbm.MEDIA_BTN_PAUSE else wasidremin.gmccpa.ocbm.Ocbm.MEDIA_BTN_PLAY)
        }
        add(bottom, play, false)
        add(bottom, railButton(RailIcon.NEXT, "Next", RailTone.Default) { _ ->
            sendMedia(wasidremin.gmccpa.ocbm.Ocbm.MEDIA_BTN_NEXT)
        }, false)
        add(bottom, railButton(RailIcon.PHONE, "Phone Home", RailTone.Default) { _ ->
            sendNav(wasidremin.gmccpa.ocbm.Ocbm.NAV_HOME)
        }, false)
        add(bottom, railButton(RailIcon.BACK, "Back", RailTone.Alert) { _ ->
            sendNav(wasidremin.gmccpa.ocbm.Ocbm.NAV_BACK)
        }, false)
        rail.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        rail.addView(View(this), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        rail.addView(bottom, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return rail
    }

    /** Local play/pause, same as the other app: the phone has no button-state we can read from here. */
    private var railAssumedPlaying = false

    private fun railGroup() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun railButton(icon: RailIcon, description: String, tone: RailTone, onClick: (RailButton) -> Unit) =
        RailButton(this, icon, description, tone, onClick)

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
    private fun openSettings() {
        DisplayPrefs.holdLauncher = true
        log.i("settings — holding the launcher in front; the session stays up")
        startActivity(
            Intent(this, wasidremin.gmccpa.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }

    private fun openHome() {
        log.i("home — leaving the picture; the session stays up")
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Media keys share the event-channel lock with touch. Never send them on the UI thread. */
    private fun sendMedia(index: Byte) {
        if (index == wasidremin.gmccpa.ocbm.Ocbm.MEDIA_BTN_PLAY) {
            MediaTransportClock.playSentAt = android.os.SystemClock.elapsedRealtime()
        }
        touchHandler?.post {
            val sent = if (wasidremin.gmccpa.ocbm.AdapterWifi.enabled(this))
                wasidremin.gmccpa.ocbm.AdapterSession.sendMediaButton(index)
            else
                wasidremin.gmccpa.pair.NativeCore.mediaButton(index.toInt())
            if (!sent) log.w("media button $index refused — no live session")
            else log.i("media button $index sent")
        }
    }

    /** Siri is a press and a release. A bare requestSiri is ignored by iOS. */
    private fun sendSiri() {
        val h = touchHandler ?: return
        h.post {
            if (!dispatchCommand(wasidremin.gmccpa.ocbm.Ocbm.CMD_SIRI_DOWN)) return@post
            h.postDelayed({ dispatchCommand(wasidremin.gmccpa.ocbm.Ocbm.CMD_SIRI_UP) }, 120)
        }
    }

    private fun sendNav(nav: Byte) {
        touchHandler?.post { dispatchNav(nav) }
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
        micUplink = MicUplink().also { it.start() }

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
        serve(gen, aus, "audio") { ins -> p.consume(ins) }

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
            wasidremin.gmccpa.ocbm.AdapterSession.attachVideo(holder.surface)
            SessionTrace.Board.up(BOARD_RENDERER, "Surface attached — USB HEVC ${VideoFrame.width}x${VideoFrame.height}")
            return
        }
        if (renderer != null) { log.i("renderer already attached"); return }
        val r = HevcRenderer(VideoFrame.width, VideoFrame.height, holder.surface) { requestKeyframe() }
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
            nowPlaying.clear()
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
        nowPlaying.clear()
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
                log.i("requestViewArea index=$idx -> laying the surface out at ${VIEW_AREAS[idx]}")
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
     * around the Siri turn in the 2026-09-09 capture. appStateID 2/3 are believed to be
     * PhoneCall/TurnByTurn (AirPlayCommon.h order, unverified in this checkout) and are passed for
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
        val from = VIEW_AREAS[viewAreaIndex]
        val to = VIEW_AREAS[index]
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
        val framed = VideoFrame.railPx > 0 && viewAreaIndex == 0
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
        val tw = if (VideoFrame.railPx > 0 && viewAreaIndex == 0) VideoFrame.width else DISPLAY_W
        val th = if (VideoFrame.railPx > 0 && viewAreaIndex == 0) VideoFrame.height else DISPLAY_H
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemUi()
    }

    override fun onDestroy() {
        // Only clear the shared handle if it still points at US. A newer generation may already have
        // published itself (launch of the replacement can precede this teardown), and clearing it
        // unconditionally would leave the LIVE screen unreachable from [onSessionEnded].
        if (live?.get() === this) live = null
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
        val r = minOf(width, height) / 2f
        disc.color = tone.container
        disc.alpha = when {
            !isEnabled -> 90
            isPressed -> 200
            else -> 255
        }
        canvas.drawCircle(cx, cy, r, disc)
        ink.color = tone.ink
        ink.alpha = if (isEnabled) 255 else 120
        val iconPx = minOf(width, height) * (30f / 72f)
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
