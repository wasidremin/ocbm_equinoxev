package wasidremin.gmccpa.pane

import android.content.Context
import wasidremin.gmccpa.ProbeLog
import wasidremin.gmccpa.ocbm.PixelRect
import wasidremin.gmccpa.ocbm.ViewAreaRule

/**
 * The split layout (Settings ▸ Display ▸ Media pane): CarPlay keeps one side of the panel, full
 * height, and this app's media pane ([MediaPaneView]) takes the rest. Ported in shape from enigy/carlink
 * `ocbm/SplitViewArea.kt` (Unlicense).
 *
 * CarPlay's side is declared as the main stream's `viewAreas[1]` with `initial: true`, so the session
 * starts split. iOS then offers its own switch between the two areas (`viewAreaTransitionControl`, set
 * by the box when a second area exists) and the box relays its `requestViewArea` to the app; the pane's
 * own full-screen button sends `CMD_VIEW_AREA 0`. The picture itself is never cropped: iOS draws black
 * outside its area, and the pane is laid over exactly that strip.
 *
 * The area is declared at subscribe, so a change here takes effect at the next connect.
 */
object SplitPane {
    private val log = ProbeLog.sub("pane")

    enum class Side(val label: String) { OFF("Off"), RIGHT("Right"), LEFT("Left") }

    /** The choices Settings cycles through: side, then the pane's share of the panel. */
    data class Choice(val side: Side, val percent: Int) {
        val label: String get() = if (side == Side.OFF) "Off" else "${side.label}, $percent%"
    }

    val CHOICES = listOf(
        Choice(Side.OFF, 0),
        Choice(Side.RIGHT, 30), Choice(Side.RIGHT, 40),
        Choice(Side.LEFT, 30), Choice(Side.LEFT, 40),
    )

    /** How long a switch between the split and full screen animates (enigy measured 1 s as 1.4 s tap-to-picture). */
    const val TRANSITION_MS = 500

    private const val PREFS = "media_pane"
    private const val KEY_SIDE = "side"
    private const val KEY_PERCENT = "percent"

    fun choice(ctx: Context): Choice {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val side = runCatching { Side.valueOf(p.getString(KEY_SIDE, Side.OFF.name)!!) }.getOrDefault(Side.OFF)
        return CHOICES.firstOrNull { it.side == side && (side == Side.OFF || it.percent == p.getInt(KEY_PERCENT, 30)) }
            ?: CHOICES.first()
    }

    fun setChoice(ctx: Context, c: Choice) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SIDE, c.side.name).putInt(KEY_PERCENT, c.percent).apply()
        log.i("pane: ${c.label} (Settings; applies at the next connect)")
    }

    /**
     * CarPlay's rectangle for a pane of [percent] of a [panelW]x[panelH] panel on [side], or null when
     * no legal one exists (then the session comes up full screen). The width rounds DOWN to even, as
     * every value must; an area that would tear the session down or fall below the product floor is
     * not declared. On a 2400x960 panel, 30% on the right is 1680x960 at 0,0.
     */
    fun carPlayArea(panelW: Int, panelH: Int, side: Side, percent: Int): PixelRect? {
        if (side == Side.OFF || percent !in 1..99) return null
        val carW = (panelW * (100 - percent) / 100) and 1.inv()
        val x = if (side == Side.LEFT) panelW - carW else 0
        val a = PixelRect(x, 0, carW, panelH and 1.inv())
        if (ViewAreaRule.teardownVerdict(a, panelW, panelH) != null) return null
        if (ViewAreaRule.floorVerdict(a.width, a.height) != null) return null
        return a
    }

    // ---- this session ---------------------------------------------------------------------------

    /** The area declared at this session's subscribe, or null when it is full screen only. */
    @Volatile var declared: PixelRect? = null
        private set
    /** The view area iOS is showing: 1 split (the session starts there when [declared]), 0 full. */
    @Volatile var index: Int = 0
        private set
    @Volatile var listener: (() -> Unit)? = null

    /** At subscribe: what this session declares. Any thread. */
    fun onSubscribe(area: PixelRect?) {
        declared = area
        index = if (area != null) 1 else 0
        log.i(if (area != null) "pane: declared CarPlay area ${area.width}x${area.height}@${area.x},${area.y}, session starts split"
            else "pane: no split this session")
        listener?.invoke()
    }

    /** iOS's own switch, relayed by the box, or the pane's full-screen button. Any thread. */
    fun onViewArea(i: Int, why: String) {
        if (declared == null || i !in 0..1 || i == index) return
        index = i
        log.i("pane: view area $i (${if (i == 1) "split" else "full screen"}) — $why")
        listener?.invoke()
    }
}
