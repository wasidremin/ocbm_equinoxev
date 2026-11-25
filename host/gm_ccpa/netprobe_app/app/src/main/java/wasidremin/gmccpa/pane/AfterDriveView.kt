package wasidremin.gmccpa.pane

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import wasidremin.gmccpa.EqTheme
import wasidremin.gmccpa.telemetry.DriveSummary
import wasidremin.gmccpa.telemetry.RollingEfficiency
import java.util.Locale

/**
 * The after-drive summary card (T28) over the CarPlay picture, in the "card and month" layout the
 * owner chose (2026-11-24). Drawn on a 1100 × 820 design grid scaled to fit. Only a touch on the
 * card is taken (it closes the card); the rest of the screen stays CarPlay's.
 */
class AfterDriveView(ctx: Context, private val onClose: () -> Unit) : View(ctx) {
    private var s: DriveSummary.Summary? = null
    private var metric = false
    private var monthName = ""

    private val card = RectF()
    private val r = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    /**
     * The CarPlay picture's bounds in this view's pixels, read at each draw; the card centres on it
     * (null: the whole view). The picture already sits under the custom top bar and inside the
     * traced glass. Emulator, 2026-11-24: the card placed by the window ran under the top bar, and
     * the window's insets read 0 in Full mode while the emulator still drew its bars.
     */
    var area: () -> android.graphics.Rect? = { null }

    fun bind(summary: DriveSummary.Summary?, metric: Boolean) {
        s = summary
        this.metric = metric
        monthName = summary?.let {
            java.text.SimpleDateFormat("MMMM", Locale.getDefault()).format(java.util.Date(it.t1))
        }.orEmpty()
        visibility = if (summary == null) GONE else VISIBLE
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (s == null || !card.contains(e.x, e.y)) return false
        if (e.action == MotionEvent.ACTION_UP) onClose()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val s = s ?: return
        val a = area()?.takeIf { it.width() > 0 && it.height() > 0 } ?: android.graphics.Rect(0, 0, width, height)
        val k = minOf(a.width() * 0.66f / W, a.height() * 0.92f / H)
        val left = a.left + (a.width() - W * k) / 2f
        val top = a.top + (a.height() - H * k) / 2f
        card.set(left, top, left + W * k, top + H * k)
        fun x(v: Float) = left + v * k
        fun y(v: Float) = top + v * k

        fill.color = EqTheme.BG_TOP
        canvas.drawRoundRect(card, 28 * k, 28 * k, fill)
        fill.color = EqTheme.CARD_LINE
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = 2 * k
        canvas.drawRoundRect(card, 28 * k, 28 * k, fill)
        fill.style = Paint.Style.FILL

        fun say(str: String, px: Float, baseline: Float, size: Float, color: Int, heavy: Boolean = false, right: Boolean = false) {
            text.textSize = size * k
            text.color = color
            text.typeface = if (heavy) bold else Typeface.DEFAULT
            text.textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
            canvas.drawText(str, x(px), y(baseline), text)
        }

        // Header
        say("LAST DRIVE", PAD, 70f, 26f, EqTheme.TEXT_DIM, heavy = true)
        say(DriveSummary.clockRange(s.t0, s.t1, s.tzMin) + "     ✕", W - PAD, 70f, 26f, EqTheme.TEXT_DIM, right = true)

        // The figure and how it compares
        val figure = DriveSummary.rate(s.whPerKm, metric)
        say(figure, PAD, 180f, 96f, EqTheme.TEXT, heavy = true)
        text.textSize = 96f * k; text.typeface = bold
        val unitX = PAD + text.measureText(figure) / k + 14f
        say(DriveSummary.rateUnit(metric), unitX, 180f, 32f, EqTheme.TEXT_DIM)
        DriveSummary.versus(s, metric)?.let { v ->
            val tone = when (s.verdict?.standing) {
                wasidremin.gmccpa.telemetry.TripStats.Standing.BETTER -> EqTheme.OK
                wasidremin.gmccpa.telemetry.TripStats.Standing.WORSE -> EqTheme.WARN
                else -> EqTheme.TEXT_DIM
            }
            say(v, W - PAD, 180f, 32f, tone, right = true)
        }
        say(DriveSummary.facts(s, metric), PAD, 240f, 28f, EqTheme.TEXT)

        // Where it went
        val parts = DriveSummary.parts(s)
        val most = parts.maxOf { it.wh }.coerceAtLeast(1.0)
        var row = 300f
        for (p in parts) {
            say(p.label, PAD, row + 30f, 28f, EqTheme.TEXT_DIM)
            val barW = ((W - PAD - 200f - BAR_X) * (p.wh / most)).toFloat().coerceAtLeast(6f)
            fill.color = if (p.label == "Driving") EqTheme.ACCENT else EqTheme.WARN
            r.set(x(BAR_X), y(row + 8f), x(BAR_X + barW), y(row + 34f))
            canvas.drawRoundRect(r, 6 * k, 6 * k, fill)
            say(DriveSummary.kwh(p.wh), W - PAD, row + 30f, 28f, EqTheme.TEXT, right = true)
            row += 46f
        }
        DriveSummary.regen(s)?.let { say(it, PAD, row + 30f, 26f, EqTheme.OK) }

        // The last drives and the month
        fill.color = EqTheme.DIVIDER
        canvas.drawRect(x(PAD), y(500f), x(W - PAD), y(502f), fill)
        say("LAST ${s.recent.size} DRIVES", PAD, 560f, 24f, EqTheme.TEXT_DIM, heavy = true)
        drawRecent(canvas, s, ::x, ::y, k)
        DriveSummary.monthLine(s, monthName, metric)?.let { say(it, PAD, 720f, 30f, EqTheme.TEXT) }
        DriveSummary.lifetimeLine(s, metric)?.let { say(it, PAD, 775f, 28f, EqTheme.TEXT_DIM) }
    }

    /** One bar per drive, height its mi/kWh (km/kWh), this drive last and in the accent. */
    private fun drawRecent(canvas: Canvas, s: DriveSummary.Summary, x: (Float) -> Float, y: (Float) -> Float, k: Float) {
        val vals = s.recent.map { RollingEfficiency.perKwh(it, metric) }
        if (vals.isEmpty()) return
        val lo = vals.min() * 0.8
        val hi = vals.max().coerceAtLeast(lo + 0.1)
        val x0 = 330f
        val step = (W - PAD - x0) / DriveSummary.RECENT
        vals.forEachIndexed { i, v ->
            val h = (20 + 90 * (v - lo) / (hi - lo)).toFloat()
            val last = i == vals.lastIndex
            fill.color = if (last) EqTheme.ACCENT else EqTheme.TEXT_FAINT
            val bx = x0 + (i + DriveSummary.RECENT - vals.size) * step
            r.set(x(bx + step * 0.18f), y(640f - h), x(bx + step * 0.82f), y(640f))
            canvas.drawRoundRect(r, 4 * k, 4 * k, fill)
        }
    }

    private companion object {
        const val W = 1100f
        const val H = 820f
        const val PAD = 56f
        const val BAR_X = 230f
    }
}
