package wasidremin.gmccpa

import android.content.Context

/**
 * How the CarPlay picture shares the panel with GM's own chrome.
 *
 * Numeric values are persisted. Do not renumber them. The four modes are the view options from
 * the earlier Carlink app (`DisplayMode` in carlink_native): system bars visible, status bar
 * hidden, navigation bar hidden, and both hidden. The sidebar is a separate toggle, the same
 * way that app lifted it out of being its own mode.
 *
 * Default is full screen. That is what this app already asked the window for; on the Equinox
 * (API 34) the legacy visibility flags alone did not hide GM's bars, which is why the picture
 * sat inside the car's chrome. The insets controller is what actually hides them.
 */
enum class ScreenMode(val value: Int, val label: String) {
    SYSTEM_UI(0, "Show car bars"),
    STATUS_HIDDEN(1, "Hide top bar"),
    FULLSCREEN(2, "Full screen"),
    NAV_HIDDEN(3, "Hide bottom bar"),
    ;

    companion object {
        fun from(value: Int): ScreenMode = values().find { it.value == value } ?: FULLSCREEN
    }
}

object DisplayPrefs {
    /** Match the sister app's edge-to-edge policy BEFORE measuring the activity.
     * Hiding bars alone leaves DEFAULT cutout policy, which can clip the window outside
     * the old GM status-bar region even when visible bar insets are zero.
     */
    fun applyWindow(activity: android.app.Activity, mode: ScreenMode = mode(activity)) {
        val window = activity.window
        val hideStatus = mode == ScreenMode.FULLSCREEN || mode == ScreenMode.STATUS_HIDDEN
        val hideNav = mode == ScreenMode.FULLSCREEN || mode == ScreenMode.NAV_HIDDEN
        if (hideStatus) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val cutout = when {
                mode == ScreenMode.SYSTEM_UI -> android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                android.os.Build.VERSION.SDK_INT >= 30 -> android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else -> android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (window.attributes.layoutInDisplayCutoutMode != cutout) {
                window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = cutout }
            }
        }
        @Suppress("DEPRECATION")
        var flags = android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (hideStatus || hideNav) flags = flags or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        if (hideStatus) flags = flags or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
        if (hideNav) flags = flags or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = flags
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                val status = android.view.WindowInsets.Type.statusBars()
                val nav = android.view.WindowInsets.Type.navigationBars()
                if (hideStatus) it.hide(status) else it.show(status)
                if (hideNav) it.hide(nav) else it.show(nav)
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        window.decorView.requestApplyInsets()
    }

    private const val PREFS = "carplay_screen"
    private const val KEY_MODE = "mode"
    private const val KEY_SIDEBAR = "sidebar"
    private const val KEY_UI_SCALE = "ui_scale"
    private const val KEY_SAFE_RIGHT = "safe_right"
    private const val KEY_PHONE = "last_phone_name"
    private const val KEY_CUSTOM_TOP_BAR = "custom_top_bar"

    /** Percentages the screen page offers. 100 is today's advertised size. */
    val SCALE_PRESETS = intArrayOf(100, 115, 125, 133, 150)
    /** Right inset in 100% pixels. The advertised inset shrinks with [uiScale]. */
    val SAFE_PRESETS = intArrayOf(0, 24, 48, 72, 96)
    const val DEFAULT_UI_SCALE = 125
    const val DEFAULT_SAFE_RIGHT = 48

    /**
     * The driver opened Settings from the CarPlay screen. Process-scoped, not persisted: a fresh
     * process should come back to the picture. While this is set, [wasidremin.gmccpa.MainActivity]
     * must not immediately cover itself with CarPlay again — that relaunch is what made the
     * settings screen unreachable during a live session.
     */
    @Volatile var holdLauncher: Boolean = false

    fun mode(ctx: Context): ScreenMode =
        ScreenMode.from(prefs(ctx).getInt(KEY_MODE, ScreenMode.FULLSCREEN.value))

    fun sidebar(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SIDEBAR, false)
    /**
     * Reserve a custom top strip before the next adapter subscription when GM's status
     * bar is hidden. The negotiated video size stays fixed until reconnect.
     */
    fun customTopBar(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_CUSTOM_TOP_BAR, false)
    fun setCustomTopBar(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_CUSTOM_TOP_BAR, on).apply()
    }

    fun setMode(ctx: Context, mode: ScreenMode) {
        prefs(ctx).edit().putInt(KEY_MODE, mode.value).apply()
    }

    fun setSidebar(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SIDEBAR, on).apply()
    }

    /** CarPlay UI scale, as a percentage. A change is read at the next `CT_SUBSCRIBE`. */
    fun uiScale(ctx: Context): Int {
        val v = prefs(ctx).getInt(KEY_UI_SCALE, DEFAULT_UI_SCALE)
        return if (v in SCALE_PRESETS) v else DEFAULT_UI_SCALE
    }

    fun setUiScale(ctx: Context, percent: Int) {
        if (percent in SCALE_PRESETS) prefs(ctx).edit().putInt(KEY_UI_SCALE, percent).apply()
    }

    /**
     * Right inset in 100%-space pixels (0–96). The phone is told the scaled value, so a 48 px
     * margin stays about the same physical width when the advertised picture shrinks.
     */
    fun safeRightPx(ctx: Context): Int =
        prefs(ctx).getInt(KEY_SAFE_RIGHT, DEFAULT_SAFE_RIGHT).coerceIn(0, 96)

    fun setSafeRightPx(ctx: Context, px: Int) {
        prefs(ctx).edit().putInt(KEY_SAFE_RIGHT, px.coerceIn(0, 96)).apply()
    }

    fun lastPhoneName(ctx: Context): String? =
        prefs(ctx).getString(KEY_PHONE, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setLastPhoneName(ctx: Context, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        prefs(ctx).edit().putString(KEY_PHONE, clean).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * The video rectangle this session advertised to the phone, and the rail that sits beside it.
 *
 * The sidebar is not a crop of a fixed 2400×960 frame. When it is on at subscribe time the
 * adapter config's pixel size is the measured window minus the rail, so the phone lays CarPlay
 * out in that rectangle and the surface sits 1:1 beside the rail. 2400 is only the fallback when
 * the window has not been measured yet. A toggle after subscribe does not move [width] until the
 * next subscribe — the live stream is still the size already negotiated.
 */
object VideoFrame {
    const val PANEL_W = 2400
    const val PANEL_H = 960
    /** Matches the rail drawn in `CarPlayActivity`. Kept here so the advertised gap and the view agree. */
    const val RAIL_DP = 128

    /** Reserved before SUBSCRIBE, then used unchanged by layout and touch. */
    @Volatile var topPx: Int = 0
        private set

    fun topBarPixels(height: Int, enabled: Boolean): Int =
        if (enabled) ((height * GmTopBarView.HEIGHT_FRACTION + 1f).toInt() and 1.inv()) else 0

    /** Advertised picture, after [DisplayPrefs.uiScale]. This is the decoder and the YAML size. */
    @Volatile var width: Int = PANEL_W
        private set
    @Volatile var height: Int = PANEL_H
        private set
    /** On-screen CarPlay rectangle. The [android.view.SurfaceView] stays this size; the buffer is [width]×[height]. */
    @Volatile var viewWidth: Int = PANEL_W
        private set
    @Volatile var viewHeight: Int = PANEL_H
        private set
    /** Percent applied by the last [capture]. 100 until then, and always 100 on the wired path. */
    @Volatile var uiScalePercent: Int = 100
        private set
    /** Right inset in advertised pixels. 0 keeps a full-frame safe area. */
    @Volatile var safeRightPx: Int = 0
        private set
    /** Left gap in panel pixels. 0 when this session's picture is the full panel. */
    @Volatile var railPx: Int = 0
        private set
    /** Window width this session measured. [PANEL_W] until [capture] runs. */
    @Volatile var panelPx: Int = PANEL_W
        private set
    /** Laid-out activity width. The other app reads this after the window is up. */
    data class Measurement(
        val mode: ScreenMode,
        val width: Int,
        val height: Int,
        val x: Int,
        val y: Int,
        val stableStatusTop: Int,
        val cutoutTop: Int,
        val visibleBottom: Int,
    )

    private const val GEOMETRY_PREFS = "display_geometry"
    @Volatile private var launcherMeasurement: Measurement? = null
    @Volatile private var testingMeasurement: Measurement? = null
    @Volatile var captureSource: String = "default"
        private set
    @Volatile var captureX: Int = 0
        private set
    @Volatile var captureY: Int = 0
        private set
    @Volatile var bottomPx: Int = 0
        private set
    @Volatile var captureTopSource: String = "fraction"
        private set
    @Volatile private var statusRegionHeight: Int = 0

    private fun geometryKey(mode: ScreenMode, part: String) = "${mode.value}_$part"

    private fun persistMeasurement(ctx: Context, m: Measurement) {
        if (m.width < 640 || m.height < 480) return
        ctx.applicationContext.getSharedPreferences(GEOMETRY_PREFS, Context.MODE_PRIVATE).edit()
            .putInt(geometryKey(m.mode, "width"), m.width)
            .putInt(geometryKey(m.mode, "height"), m.height)
            .putInt(geometryKey(m.mode, "x"), m.x)
            .putInt(geometryKey(m.mode, "y"), m.y)
            .putInt(geometryKey(m.mode, "status"), m.stableStatusTop)
            .putInt(geometryKey(m.mode, "cutout"), m.cutoutTop)
            .putInt(geometryKey(m.mode, "bottom"), m.visibleBottom)
            .apply()
    }

    private fun persistedMeasurement(ctx: Context, mode: ScreenMode): Measurement? {
        val p = ctx.applicationContext.getSharedPreferences(GEOMETRY_PREFS, Context.MODE_PRIVATE)
        val w = p.getInt(geometryKey(mode, "width"), 0)
        val h = p.getInt(geometryKey(mode, "height"), 0)
        if (w < 640 || h < 480) return null
        return Measurement(mode, w, h, p.getInt(geometryKey(mode, "x"), 0),
            p.getInt(geometryKey(mode, "y"), 0), p.getInt(geometryKey(mode, "status"), 0),
            p.getInt(geometryKey(mode, "cutout"), 0), p.getInt(geometryKey(mode, "bottom"), 0))
    }

    private fun measurement(mode: ScreenMode, width: Int, height: Int, x: Int, y: Int,
                            insets: android.view.WindowInsets?): Measurement {
        val stable = if (insets != null && android.os.Build.VERSION.SDK_INT >= 30)
            insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars()).top else 0
        val cutout = if (android.os.Build.VERSION.SDK_INT >= 28) insets?.displayCutout?.safeInsetTop ?: 0 else 0
        val bottom = if (insets != null && android.os.Build.VERSION.SDK_INT >= 30 &&
            insets.isVisible(android.view.WindowInsets.Type.navigationBars()))
            insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom else 0
        return Measurement(mode, width, height, x, y, stable, cutout, bottom)
    }

    /** Called by the launcher only after this mode's insets have been dispatched. */
    fun noteLauncherWindow(ctx: Context, mode: ScreenMode, width: Int, height: Int,
                           x: Int, y: Int, insets: android.view.WindowInsets?) {
        if (testingGeometry || width < 640 || height < 480) return
        val m = measurement(mode, width, height, x, y, insets)
        launcherMeasurement = m
        persistMeasurement(ctx, m)
        statusRegionHeight = maxOf(m.stableStatusTop, m.cutoutTop)
    }

    /** CarPlay measurements update next-session geometry, but never the already latched frame. */
    fun noteCarPlayWindow(ctx: Context, mode: ScreenMode, width: Int, height: Int,
                          x: Int, y: Int, insets: android.view.WindowInsets?) {
        if (testingGeometry || width < 640 || height < 480) return
        val m = measurement(mode, width, height, x, y, insets)
        persistMeasurement(ctx, m)
    }

    /** Retained for deterministic, hardware-free geometry checks. */
    fun noteWindow(width: Int, height: Int = 0, statusRegion: Int = 0) {
        if (!testingGeometry || width < 640 || height < 480) return
        testingMeasurement = Measurement(ScreenMode.FULLSCREEN, width, height, 0, 0,
            statusRegion, 0, 0)
        statusRegionHeight = statusRegion.coerceAtLeast(0)
    }

    fun visibleBottom(insets: android.view.WindowInsets?): Int =
        if (insets != null && android.os.Build.VERSION.SDK_INT >= 30 &&
            insets.isVisible(android.view.WindowInsets.Type.navigationBars()))
            insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom else 0

    private fun measurementForCapture(ctx: Context, mode: ScreenMode): Measurement? {
        if (testingGeometry) return testingMeasurement?.takeIf { it.mode == mode }
        launcherMeasurement?.takeIf { it.mode == mode && it.width >= 640 && it.height >= 480 }
            ?.let { return it }
        return persistedMeasurement(ctx, mode)
    }

    fun logCaptureSource(mode: ScreenMode): String = "$captureSource mode=${mode.name}"

    fun topBarSource(m: Measurement): String {
        val values = listOf(m.stableStatusTop to "stableStatus", m.cutoutTop to "cutout",
            m.y.coerceAtLeast(0) to "origin")
        val best = values.maxByOrNull { it.first }
        return if (best == null || best.first <= 0) "fraction" else best.second
    }

    private fun reserveTop(m: Measurement, height: Int, enabled: Boolean): Int {
        if (!enabled) { captureTopSource = "fraction"; return 0 }
        val measured = maxOf(m.stableStatusTop, m.cutoutTop, m.y.coerceAtLeast(0))
        captureTopSource = when {
            measured <= 0 -> "fraction"
            measured == m.stableStatusTop -> "stableStatus"
            measured == m.cutoutTop -> "cutout"
            else -> "origin"
        }
        return if (measured > 0) (measured + 1) and 1.inv() else topBarPixels(height, true)
    }

    /** The capture-selected root bounds; always from a laid-out window, never maximum metrics. */
    private fun selectMeasurement(ctx: Context, mode: ScreenMode): Measurement {
        val live = measurementForCapture(ctx, mode)
        captureSource = when {
            testingGeometry && live != null -> "live"
            !testingGeometry && launcherMeasurement?.mode == mode && live != null -> "live"
            live != null -> "persisted"
            else -> "default"
        }
        val selected = live ?: Measurement(mode, PANEL_W, PANEL_H, 0, 0, 0, 0, 0)
        captureX = selected.x
        captureY = selected.y
        return selected
    }

    @Volatile var testingGeometry: Boolean = false

    /**
     * Latch size for the next adapter subscribe; never called during a live session.
     *
     * [advertise] is false on the Silverado path: that session's `/info` is a static 2400×960
     * document, so shrinking the surface here would scale the picture. The Equinox adapter path
     * is the one that sends [width] in the vehicle config.
     *
     * The latched rectangle is the actual laid-out panel minus the custom bar and rail. Hiding
     * GM's bars does not guarantee extra height (the vehicle measured 967 px, not 960 plus a
     * status bar), so the phone must lay out in the remaining rectangle.
     */
    fun capture(ctx: Context, advertise: Boolean) {
        val percent = if (advertise) DisplayPrefs.uiScale(ctx) else 100
        val inset = if (advertise) DisplayPrefs.safeRightPx(ctx) else 0
        val mode = DisplayPrefs.mode(ctx)
        if (!advertise) {
            captureSource = "default"
            captureX = 0; captureY = 0; bottomPx = 0; topPx = 0
            latch(PANEL_W, PANEL_H, 0, PANEL_W, percent, inset)
            return
        }
        val m = selectMeasurement(ctx, mode)
        val panelW = if (captureSource == "default") PANEL_W else m.width
        val panelH = if (captureSource == "default") PANEL_H else m.height
        val enabled = DisplayPrefs.customTopBar(ctx) &&
            (mode == ScreenMode.FULLSCREEN || mode == ScreenMode.STATUS_HIDDEN)
        val top = reserveTop(m, panelH, enabled)
        val bottom = m.visibleBottom.coerceIn(0, (panelH - top).coerceAtLeast(0))
        var videoH = (panelH - top - bottom) and 1.inv()
        var actualTop = top
        var actualBottom = bottom
        if (videoH < 480) {
            actualTop = 0; actualBottom = 0
            videoH = if (panelH >= 480) panelH and 1.inv() else PANEL_H
        }
        topPx = actualTop
        bottomPx = actualBottom
        statusRegionHeight = maxOf(m.stableStatusTop, m.cutoutTop)
        val rail = if (DisplayPrefs.sidebar(ctx)) railPixels(ctx) else 0
        val videoW = ((panelW - rail).coerceAtLeast(2)) and 1.inv()
        if (videoW < 640 || videoH < 480) {
            captureSource = "default"
            captureX = 0; captureY = 0; bottomPx = 0; topPx = 0
            latch(PANEL_W, PANEL_H, 0, PANEL_W, percent, inset)
            return
        }
        latch(videoW, videoH, if (rail > 0) panelW - videoW else 0, panelW, percent, inset)
    }

    /**
     * [viewW]×[viewH] is what the surface occupies. [width]×[height] is that rectangle divided
     * by the UI scale, even, which is what the phone lays out and what the decoder is configured
     * with. The inset is the 100%-space margin mapped into the advertised width.
     */
    private fun latch(viewW: Int, viewH: Int, rail: Int, panel: Int, percent: Int, insetAt100: Int) {
        viewWidth = viewW
        viewHeight = viewH
        uiScalePercent = percent
        width = DisplayScale.even(viewW, percent)
        height = DisplayScale.even(viewH, percent)
        railPx = rail
        panelPx = panel
        val scaled = DisplayScale.even(insetAt100, percent)
        safeRightPx = scaled.coerceIn(0, (width - 2).coerceAtLeast(0)) and 1.inv()
    }

    /** Pixel width from the same current-mode measurement used for the panel height. */
    fun measuredPanelWidth(ctx: Context): Int = selectMeasurement(ctx, DisplayPrefs.mode(ctx)).width

    /**
     * Pixel width of the 108 dp rail, the same conversion the other Carlink app uses
     * (`SidebarControlRailWidth.value * density`). The video width is what gets rounded even.
     */
    fun railPixels(ctx: Context): Int =
        (RAIL_DP * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1)
}

/**
 * Advertised pixels for a UI-scale percentage.
 *
 * The on-screen CarPlay rectangle is divided by the scale and rounded to the nearest even
 * number, so 2494×960 becomes 2168×834, 1996×768, 1870×720 or 1662×640. 133% is 4/3: `px*100/133`
 * misses 1870×720.
 */
object DisplayScale {
    fun even(px: Int, percent: Int): Int {
        if (px <= 0) return 0
        if (percent <= 100) return px and 1.inv()
        val n = if (percent == 133) {
            (px * 3) / 4
        } else {
            val num = px.toLong() * 100L
            val q = num / percent
            val rem = num % percent
            var nearest = if (rem * 2 >= percent.toLong()) q + 1 else q
            if (nearest % 2L != 0L) {
                val real = num.toDouble() / percent.toDouble()
                val down = nearest - 1
                val up = nearest + 1
                nearest = if (kotlin.math.abs(real - down) <= kotlin.math.abs(real - up)) down else up
            }
            nearest.toInt()
        }
        val even = if (n <= 0) 0 else n and 1.inv()
        return if (even == 0) 2 else even
    }
}
