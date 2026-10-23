package wasidremin.gmccpa

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * Screen-outline calibration. Full-panel window (bars hidden, cutout ALWAYS) so the outline is in
 * the same pixels the CarPlay window will occupy.
 *
 * The driver drags eight dots ([OutlineView]) onto the edge of the visible screen — four corners
 * and one point per side that bends the side into a triangle — then Save persists the
 * [GlassOutline] plus the largest picture rectangle inside it ([GlassOutline.maxRect]). The
 * rectangle sizes the advertised CarPlay video ([VideoFrame.capture], next connection); the
 * outline itself is what the top bar fits its tiles and clock/temperature to.
 *
 * Replaces finger-painting the dead glass, which was awkward and kept the innermost paint.
 */
class ScribbleActivity : Activity() {
    private lateinit var view: OutlineView
    private lateinit var hint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DisplayPrefs.applyWindow(this, ScreenMode.FULLSCREEN)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        view = OutlineView(this)
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        hint = TextView(this).apply {
            text = INSTRUCTIONS
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(0xB0000000.toInt())
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xB0000000.toInt())
        }
        bar.addView(cmd("Reset") { view.outline = start(fresh = true); refresh() })
        bar.addView(cmd("Cancel") { finish() })
        bar.addView(cmd("◀") { view.nudge(-NUDGE, 0f) })
        bar.addView(cmd("▲") { view.nudge(0f, -NUDGE) })
        bar.addView(cmd("▼") { view.nudge(0f, NUDGE) })
        bar.addView(cmd("▶") { view.nudge(NUDGE, 0f) })
        bar.addView(cmd("Save outline") { save() })
        // Controls sit in the middle of the panel, out of the way of every edge being outlined.
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(hint, LinearLayout.LayoutParams(-2, -2))
            addView(bar, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        }
        root.addView(panel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(root)
        view.onChanged = { refresh() }
        view.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            // Posted: changing the hint text inside a layout pass would not re-measure it.
            if (r - l != or - ol || b - t != ob - ot) view.post { view.outline = start(fresh = false); refresh() }
        }
    }

    private fun cmd(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    /**
     * The saved outline, or a box on glass the OS vouches for (the cutout safe area, pulled in a
     * little) so every dot starts visible and is dragged OUT to the edge.
     */
    private fun start(fresh: Boolean): GlassOutline? {
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        if (w < 640 || h < 480) return null
        if (!fresh) DisplayPrefs.glassOutline(this, w, h)?.let { return it }
        val insets = if (Build.VERSION.SDK_INT >= 30) view.rootWindowInsets else null
        val cut = if (Build.VERSION.SDK_INT >= 28) insets?.displayCutout else null
        val statusTop = if (insets != null && Build.VERSION.SDK_INT >= 30)
            insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top else 0
        val pad = dp(24).toFloat()
        return GlassOutline.box(
            (cut?.safeInsetLeft ?: 0) + pad,
            maxOf(cut?.safeInsetTop ?: 0, statusTop) + pad,
            w - (cut?.safeInsetRight ?: 0) - pad,
            h - (cut?.safeInsetBottom ?: 0) - pad)
    }

    private fun refresh() {
        val o = view.outline
        val r = o?.maxRect()
        view.solved = r
        hint.text = when {
            o == null -> INSTRUCTIONS
            r == null -> "$INSTRUCTIONS\nNo picture fits inside this outline yet."
            else -> "$INSTRUCTIONS\nPicture " + r.width.roundToInt() + "x" + r.height.roundToInt() +
                " at " + r.l.roundToInt() + "," + r.t.roundToInt() + " — Save when every dot is on the edge."
        }
    }

    private fun save() {
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        val o = view.outline
        val r = o?.maxRect()
        if (o == null || r == null) {
            Toast.makeText(this, "No picture fits inside this outline - move the dots out to the edges", Toast.LENGTH_LONG).show()
            return
        }
        val rect = DisplayPrefs.ScribbleRect(
            (r.l / w * 1000).roundToInt().coerceIn(0, 1000),
            (r.t / h * 1000).roundToInt().coerceIn(0, 1000),
            (r.r / w * 1000).roundToInt().coerceIn(0, 1000),
            (r.b / h * 1000).roundToInt().coerceIn(0, 1000))
        if (rect.l >= rect.r || rect.t >= rect.b) return
        val note = "outline " + w.toInt() + "x" + h.toInt() +
            " rect " + r.l.toInt() + "," + r.t.toInt() + "," + r.r.toInt() + "," + r.b.toInt() +
            " pts " + o.pts.joinToString(" ") { "" + it.x.toInt() + "," + it.y.toInt() }
        if (DisplayPrefs.saveScribble(this, rect, w.toInt(), h.toInt(), note, o.toPerMille(w, h))) {
            ProbeLog.sub("display").i("outline saved: " + note)
            Toast.makeText(this, "Saved - the top bar updates now, the picture at the next connection", Toast.LENGTH_LONG).show()
            finish()
        } else {
            Toast.makeText(this, "Could not save the outline; nothing changed", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val NUDGE = 2f
        private const val INSTRUCTIONS =
            "Drag each dot onto the edge of the visible screen: 4 corners, then the middle dot on each " +
                "side to follow any slope or curve. Tap a dot, then use the arrows to fine-tune."
    }
}
