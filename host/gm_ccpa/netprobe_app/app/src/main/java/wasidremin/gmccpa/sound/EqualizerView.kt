package wasidremin.gmccpa.sound

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import wasidremin.gmccpa.EqTheme
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The equalizer's ten bands as vertical tracks (T37). Drag a band to set it; the curve becomes Custom and
 * is saved when the finger lifts. [editable] is asked at each touch: false (out of Park) leaves the bands
 * as they are and says why through [onBlocked]. The geometry is pure ([Geometry]) and tested.
 */
class EqualizerView(
    ctx: Context,
    private val editable: () -> Boolean,
    private val onBlocked: () -> Unit,
    private val onEdit: (gains: DoubleArray, done: Boolean) -> Unit,
) : View(ctx) {
    private var gains = DoubleArray(10)
    private var band = -1
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val dp = ctx.resources.displayMetrics.density

    fun show(g: DoubleArray) { gains = g.copyOf(); invalidate() }

    private fun geometry() = Geometry(width.toFloat(), height.toFloat(), 28 * dp, 30 * dp)

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), (240 * dp).roundToInt())

    override fun onDraw(c: Canvas) {
        val g = geometry()
        val zero = g.yFor(0.0)
        line.color = EqTheme.DIVIDER; line.strokeWidth = 1 * dp
        c.drawLine(g.left, zero, g.right, zero, line)
        text.textSize = 13 * dp
        for (i in gains.indices) {
            val x = g.xFor(i)
            line.color = EqTheme.CARD_LINE; line.strokeWidth = 4 * dp; line.strokeCap = Paint.Cap.ROUND
            c.drawLine(x, g.top, x, g.bottom, line)
            val y = g.yFor(gains[i])
            line.color = EqTheme.ACCENT
            c.drawLine(x, zero, x, y, line)
            line.style = Paint.Style.FILL
            c.drawCircle(x, y, if (i == band) 12 * dp else 9 * dp, line)
            text.color = EqTheme.TEXT_DIM; text.typeface = Typeface.DEFAULT
            c.drawText(Equalizer.bandLabel(Equalizer.BANDS_HZ[i]), x, height - 8 * dp, text)
            text.color = if (gains[i] == 0.0) EqTheme.TEXT_DIM else EqTheme.TEXT
            text.typeface = Typeface.DEFAULT_BOLD
            c.drawText(String.format(Locale.US, "%+.1f", gains[i]).replace("+0.0", "0"), x, 18 * dp, text)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val g = geometry()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!editable()) { onBlocked(); return true }
                band = g.bandAt(e.x)
                parent?.requestDisallowInterceptTouchEvent(true)
                set(g, e.y, done = false)
            }
            MotionEvent.ACTION_MOVE -> if (band >= 0) set(g, e.y, done = false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (band >= 0) {
                set(g, e.y, done = true); band = -1; invalidate()
            }
        }
        return true
    }

    private fun set(g: Geometry, y: Float, done: Boolean) {
        val v = g.dbAt(y)
        if (v != gains[band] || done) {
            gains = gains.copyOf().also { it[band] = v }
            onEdit(gains, done)
            invalidate()
        }
    }

    /** Pure: where the bands and the dB scale sit in a view of [w] by [h], with [padX] at the sides and [padY] above and below. */
    class Geometry(private val w: Float, private val h: Float, private val padX: Float, private val padY: Float) {
        val left get() = padX
        val right get() = w - padX
        val top get() = padY
        val bottom get() = h - padY
        private val step get() = (right - left) / 9f

        fun xFor(band: Int): Float = left + step * band
        fun yFor(db: Double): Float = (top + (bottom - top) * (Equalizer.MAX_DB - db) / (2 * Equalizer.MAX_DB)).toFloat()
        fun bandAt(x: Float): Int = ((x - left) / step).roundToInt().coerceIn(0, 9)
        fun dbAt(y: Float): Double = Equalizer.snap(Equalizer.MAX_DB - (y - top) / (bottom - top) * 2 * Equalizer.MAX_DB)
    }
}
