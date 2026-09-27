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
    private const val PREFS = "carplay_screen"
    private const val KEY_MODE = "mode"
    private const val KEY_SIDEBAR = "sidebar"
    private const val KEY_UI_SCALE = "ui_scale"
    private const val KEY_SAFE_RIGHT = "safe_right"
    private const val KEY_PHONE = "last_phone_name"

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
    const val RAIL_DP = 108

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
    @Volatile private var laidOutWidth: Int = 0

    /**
     * Latch the size the next adapter subscribe will advertise.
     *
     * [advertise] is false on the Silverado path: that session's `/info` is a static 2400×960
     * document, so shrinking the surface here would scale the picture. The Equinox adapter path
     * is the one that sends [width] in the vehicle config.
     */
    fun capture(ctx: Context, advertise: Boolean) {
        val percent = if (advertise) DisplayPrefs.uiScale(ctx) else 100
        val inset = if (advertise) DisplayPrefs.safeRightPx(ctx) else 0
        if (!advertise || !DisplayPrefs.sidebar(ctx)) {
            latch(PANEL_W, PANEL_H, 0, PANEL_W, percent, inset)
            return
        }
        // The other Carlink app sizes from the activity window after layout, not from the
        // maximum WindowMetrics. On this Equinox those disagree: the window lays out at 2479
        // and currentWindowMetrics reports 2914, which encoded a picture past the right edge.
        val panel = measuredPanelWidth(ctx)
        val rail = railPixels(ctx)
        val w = (panel - rail).coerceAtLeast(2) and 1.inv()
        if (w < 640) {
            latch(PANEL_W, PANEL_H, 0, PANEL_W, percent, inset)
            return
        }
        latch(w, PANEL_H, panel - w, panel, percent, inset)
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

    /** Record the activity's laid-out width. [MainActivity] calls this once the decor has a size. */
    fun noteWindow(width: Int) {
        if (width >= 640) laidOutWidth = width
    }

    /**
     * Pixel width of the window the picture has to fill.
     *
     * Prefer the laid-out activity, which is what the other app uses (`currentWindowMetrics`
     * read from the activity after display mode is applied). The application WindowManager's
     * current metrics on this head unit are the maximum bounds, 2914, and a picture that wide
     * runs off the right of the 2479 window.
     */
    fun measuredPanelWidth(ctx: Context): Int {
        val laid = laidOutWidth
        if (laid >= 640) return laid
        val px = ctx.resources.displayMetrics.widthPixels
        return if (px in 640..2560) px else PANEL_W
    }

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
