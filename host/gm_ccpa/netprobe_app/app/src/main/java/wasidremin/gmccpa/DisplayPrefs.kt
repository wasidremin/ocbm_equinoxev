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

    fun summary(ctx: Context): String {
        val side = if (sidebar(ctx)) "sidebar on" else "sidebar off"
        return "Screen:  ${mode(ctx).label}   ·   $side   ·   tap to change"
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * The video rectangle this session advertised to the phone, and the rail that sits beside it.
 *
 * The sidebar is not a crop of the 2400×960 frame. When it is on at subscribe time the adapter
 * config's pixel size is the panel minus the rail, so the phone lays CarPlay out in that narrower
 * rectangle. The surface is then placed 1:1 to the right of the rail. A toggle after subscribe does
 * not move [width] until the next subscribe — the live stream is still the size already negotiated.
 */
object VideoFrame {
    const val PANEL_W = 2400
    const val PANEL_H = 960
    /** Matches the rail drawn in `CarPlayActivity`. Kept here so the advertised gap and the view agree. */
    const val RAIL_DP = 108

    @Volatile var width: Int = PANEL_W
        private set
    @Volatile var height: Int = PANEL_H
        private set
    /** Left gap in panel pixels. 0 when this session's picture is the full panel. */
    @Volatile var railPx: Int = 0
        private set

    /**
     * Latch the size the next adapter subscribe will advertise.
     *
     * [advertise] is false on the Silverado path: that session's `/info` is a static 2400×960
     * document, so shrinking the surface here would scale the picture. The Equinox adapter path
     * is the one that sends [width] in the vehicle config.
     */
    fun capture(ctx: Context, advertise: Boolean) {
        if (!advertise || !DisplayPrefs.sidebar(ctx)) {
            width = PANEL_W
            height = PANEL_H
            railPx = 0
            return
        }
        val rail = railPixels(ctx)
        val w = (PANEL_W - rail).coerceAtLeast(2) and 1.inv()
        if (w < 640) {
            width = PANEL_W
            height = PANEL_H
            railPx = 0
            return
        }
        width = w
        height = PANEL_H
        railPx = PANEL_W - w
    }

    /**
     * Pixel width of the 108 dp rail, the same conversion the other Carlink app uses
     * (`SidebarControlRailWidth.value * density`). The video width is what gets rounded even.
     */
    fun railPixels(ctx: Context): Int =
        (RAIL_DP * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
