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

/**
 * Edge-tracing calibration. Full-panel window (bars hidden, cutout ALWAYS) so the trace covers
 * the same pixels the CarPlay window will occupy.
 *
 * The driver paints the DEAD glass — the sloped top, the swooping right cutout, and the
 * straight left/bottom for completeness — then Save solves the largest visible rectangle with
 * [ScribbleSolver] and persists it. The strip above the solved rect becomes the custom top
 * bar's space; the rect itself replaces the four manual dp inset sliders, which this flow
 * retires. Takes effect at the next connection ([VideoFrame.capture] reads it at subscribe).
 */
class ScribbleActivity : Activity() {
    private lateinit var scribble: ScribbleView
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
        scribble = ScribbleView(this)
        root.addView(scribble, FrameLayout.LayoutParams(-1, -1))
        hint = TextView(this).apply {
            text = INSTRUCTIONS
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(0xB0000000.toInt())
        }
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xB0000000.toInt())
        }
        bar.addView(cmd("Clear") { scribble.clear() })
        bar.addView(cmd("Cancel") { finish() })
        bar.addView(cmd("Save trace") { save() })
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        setContentView(root)
        scribble.onChanged = { refreshHint() }
        root.post { refreshHint() }
    }

    private fun cmd(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun seed(): ScribbleSolver.Seed {
        val w = scribble.width
        val h = scribble.height
        val insets = if (Build.VERSION.SDK_INT >= 30) scribble.rootWindowInsets else null
        val cut = if (Build.VERSION.SDK_INT >= 28) insets?.displayCutout else null
        val statusTop = if (insets != null && Build.VERSION.SDK_INT >= 30)
            insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top else 0
        val navBottom = if (insets != null && Build.VERSION.SDK_INT >= 30 &&
            insets.isVisible(WindowInsets.Type.navigationBars()))
            insets.getInsets(WindowInsets.Type.navigationBars()).bottom else 0
        return ScribbleSolver.Seed(
            l = (cut?.safeInsetLeft ?: 0).toFloat(),
            t = maxOf(cut?.safeInsetTop ?: 0, statusTop).toFloat(),
            r = (w - (cut?.safeInsetRight ?: 0)).toFloat(),
            b = (h - maxOf(cut?.safeInsetBottom ?: 0, navBottom)).toFloat())
    }

    private fun currentResult(): ScribbleSolver.Result? {
        val w = scribble.width
        val h = scribble.height
        if (w < 640 || h < 480) return null
        return ScribbleSolver.solve(w.toFloat(), h.toFloat(), seed(), scribble.strokeLists())
    }

    private fun refreshHint() {
        val r = currentResult()
        if (r == null) {
            hint.text = INSTRUCTIONS
            return
        }
        val cov = r.coverage
        val weak = cov.minByOrNull { it.value }
        val summary = "L" + pct(cov["left"]) + " T" + pct(cov["top"]) +
            " R" + pct(cov["right"]) + " B" + pct(cov["bottom"]) +
            " - " + scribble.strokeCount + " strokes"
        hint.text = if (weak != null && weak.value < 0.4f) {
            summary + "\nTrace the " + weak.key + " edge end to end."
        } else {
            summary + "\nRect " + r.l.toInt() + "," + r.t.toInt() + " " +
                (r.r - r.l).toInt() + "x" + (r.b - r.t).toInt() + " - Save when happy."
        }
    }

    private fun pct(f: Float?): Int = ((f ?: 0f) * 100).toInt()

    private fun save() {
        val w = scribble.width
        val h = scribble.height
        val r = currentResult()
        if (r == null) {
            Toast.makeText(this, "Window not measured yet - wait a second and retry", Toast.LENGTH_LONG).show()
            return
        }
        if (scribble.strokeLists().flatten().size < 12) {
            Toast.makeText(this, "Trace at least one edge before saving", Toast.LENGTH_LONG).show()
            return
        }
        val rect = ScribbleSolver.toFractions(r, w.toFloat(), h.toFloat())
        val note = "trace " + w + "x" + h +
            " rect " + r.l.toInt() + "," + r.t.toInt() + "," + r.r.toInt() + "," + r.b.toInt() +
            " cov L" + pct(r.coverage["left"]) + " T" + pct(r.coverage["top"]) +
            " R" + pct(r.coverage["right"]) + " B" + pct(r.coverage["bottom"])
        if (DisplayPrefs.saveScribble(this, rect, w, h, note)) {
            ProbeLog.sub("display").i("scribble saved: " + note)
            Toast.makeText(this, "Saved - takes effect at the next connection", Toast.LENGTH_LONG).show()
            finish()
        } else {
            Toast.makeText(this, "Could not save the trace; nothing changed", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val INSTRUCTIONS =
            "Paint the DEAD glass with a finger: the sloped top, the cut right side, left + bottom. Corners count for both edges."
    }
}
