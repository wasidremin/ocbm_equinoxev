package wasidremin.gmccpa.pane

import android.content.Context
import wasidremin.gmccpa.ProbeLog
import wasidremin.gmccpa.ocbm.PixelRect
import wasidremin.gmccpa.ocbm.ViewAreaRule

/**
 * The media pane ([MediaPaneView]), for the passenger, in any gear (Settings ▸ Display ▸ Media pane).
 * A tap on the strip of glass right of the CarPlay picture shows or hides it, and so does the button
 * at its top; the choice is kept for the next drive.
 *
 * Two ways to show it:
 *  - **Over CarPlay** (the default): the pane is laid over the right [OVER_PERCENT] of the picture.
 *    Nothing is sent to the phone, so it works in every session and shows at once; what CarPlay draws
 *    under it is hidden while it is up.
 *  - **Beside CarPlay** (right or left, 30 or 40%): CarPlay makes room. Its side is declared as the
 *    main stream's `viewAreas[1]`, `initial: true` when the pane was showing at the end of the last
 *    drive, and the pane is laid over the strip iOS leaves black. Showing and hiding are a view-area
 *    switch: the strip and the pane's button send `CMD_VIEW_AREA`, and iOS's own switch
 *    (`viewAreaTransitionControl`, set by the box when a second area exists) is relayed back by the
 *    box. Ported in shape from enigy/carlink `ocbm/SplitViewArea.kt` (Unlicense). The area is
 *    declared at subscribe, so choosing this takes effect at the next connect.
 */
object SplitPane {
    private val log = ProbeLog.sub("pane")

    enum class Side { OFF, OVER, RIGHT, LEFT }

    /** The choices Settings cycles through: where the pane goes, and for a split its share of the panel. */
    data class Choice(val side: Side, val percent: Int) {
        val label: String get() = when (side) {
            Side.OFF -> "Off"
            Side.OVER -> "Over CarPlay"
            Side.RIGHT -> "Right, $percent%"
            Side.LEFT -> "Left, $percent%"
        }
    }

    /** The share of the CarPlay picture an Over CarPlay pane covers. */
    const val OVER_PERCENT = 30

    val CHOICES = listOf(
        Choice(Side.OVER, OVER_PERCENT),
        Choice(Side.RIGHT, 30), Choice(Side.RIGHT, 40),
        Choice(Side.LEFT, 30), Choice(Side.LEFT, 40),
        Choice(Side.OFF, 0),
    )

    /** How long a switch between the split and full screen animates (enigy measured 1 s as 1.4 s tap-to-picture). */
    const val TRANSITION_MS = 500

    private const val PREFS = "media_pane"
    private const val KEY_SIDE = "side"
    private const val KEY_PERCENT = "percent"
    private const val KEY_SHOWN = "shown"

    fun choice(ctx: Context): Choice {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val side = runCatching { Side.valueOf(p.getString(KEY_SIDE, Side.OVER.name)!!) }.getOrDefault(Side.OVER)
        return CHOICES.firstOrNull { it.side == side && (side == Side.OFF || side == Side.OVER || it.percent == p.getInt(KEY_PERCENT, 30)) }
            ?: CHOICES.first()
    }

    fun setChoice(ctx: Context, c: Choice) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SIDE, c.side.name).putInt(KEY_PERCENT, c.percent).apply()
        // Off and Over CarPlay send nothing to the phone, so between the two a live session follows
        // at once; anything involving a split waits for the next connect, when its area is declared.
        val now = app != null && mode != Mode.SPLIT && (c.side == Side.OFF || c.side == Side.OVER)
        log.i("pane: ${c.label} (Settings; ${if (now) "now" else "applies at the next connect"})")
        if (now) {
            mode = if (c.side == Side.OFF) Mode.NONE else Mode.OVER
            shown = mode == Mode.OVER && wantShown(ctx)
            listener?.invoke()
        }
    }

    /** Whether the pane was showing when it was last shown or hidden. Hidden until the first tap. */
    fun wantShown(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOWN, false)

    /**
     * CarPlay's rectangle for a pane of [percent] of a [panelW]x[panelH] panel on [side], or null when
     * the side is not a split or no legal one exists (then the pane goes over CarPlay). The width
     * rounds DOWN to even, as every value must; an area that would tear the session down or fall below
     * the product floor is not declared. On a 2400x960 panel, 30% on the right is 1680x960 at 0,0.
     */
    fun carPlayArea(panelW: Int, panelH: Int, side: Side, percent: Int): PixelRect? {
        if ((side != Side.RIGHT && side != Side.LEFT) || percent !in 1..99) return null
        val carW = (panelW * (100 - percent) / 100) and 1.inv()
        val x = if (side == Side.LEFT) panelW - carW else 0
        val a = PixelRect(x, 0, carW, panelH and 1.inv())
        if (ViewAreaRule.teardownVerdict(a, panelW, panelH) != null) return null
        if (ViewAreaRule.floorVerdict(a.width, a.height) != null) return null
        return a
    }

    // ---- this session ---------------------------------------------------------------------------

    /** How this session shows the pane. */
    enum class Mode { NONE, OVER, SPLIT }

    @Volatile var mode: Mode = Mode.NONE
        private set
    /** CarPlay's area when [mode] is [Mode.SPLIT], else null. */
    @Volatile var declared: PixelRect? = null
        private set
    /** The pane is up: for a split, iOS is showing area 1. */
    @Volatile var shown: Boolean = false
        private set
    @Volatile var listener: (() -> Unit)? = null
    @Volatile private var app: Context? = null

    /**
     * At subscribe: [c] is the Settings choice and [area] CarPlay's side when the sent document really
     * declared it; [startsSplit] whether it declared `initial`. A split choice whose area could not be
     * declared falls back to over CarPlay. Any thread.
     */
    fun onSubscribe(ctx: Context, c: Choice, area: PixelRect?, startsSplit: Boolean) {
        app = ctx.applicationContext
        mode = when {
            c.side == Side.OFF -> Mode.NONE
            area != null -> Mode.SPLIT
            else -> Mode.OVER
        }
        declared = area.takeIf { mode == Mode.SPLIT }
        shown = when (mode) {
            Mode.NONE -> false
            Mode.SPLIT -> startsSplit
            Mode.OVER -> wantShown(ctx)
        }
        log.i(when (mode) {
            Mode.NONE -> "pane: off this session"
            Mode.SPLIT -> "pane: declared CarPlay area ${area!!.width}x${area.height}@${area.x},${area.y}, session starts ${if (shown) "split" else "full screen"}"
            Mode.OVER -> "pane: over CarPlay this session, ${if (shown) "showing" else "hidden"}"
        })
        listener?.invoke()
    }

    /** Over CarPlay: show or hide at once. Any thread. */
    fun setShown(on: Boolean, why: String) {
        if (mode != Mode.OVER || on == shown) return
        shown = on
        remember(on)
        log.i("pane: ${if (on) "shown" else "hidden"} — $why")
        listener?.invoke()
    }

    /** A split's view area changed: iOS's own switch relayed by the box, or one this app sent. Any thread. */
    fun onViewArea(i: Int, why: String) {
        if (mode != Mode.SPLIT || i !in 0..1 || (i == 1) == shown) return
        shown = i == 1
        remember(shown)
        log.i("pane: view area $i (${if (i == 1) "split" else "full screen"}) — $why")
        listener?.invoke()
    }

    private fun remember(on: Boolean) {
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean(KEY_SHOWN, on)?.apply()
    }
}
