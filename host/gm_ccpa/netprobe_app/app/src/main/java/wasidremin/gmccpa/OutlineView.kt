package wasidremin.gmccpa

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Full-window editor for the eight-point [GlassOutline]. Framework-only.
 *
 * Each dot is dragged onto the edge of the visible screen. Drags are RELATIVE (the dot keeps its
 * offset from the finger), so the finger never has to cover the point it is placing, and the
 * last-touched dot stays selected for the 1-px nudge buttons in [ScribbleActivity]. Off-glass is
 * dimmed red and the largest picture rectangle that fits is shown in green, both live.
 */
class OutlineView(ctx: Context) : View(ctx) {
    private val density = ctx.resources.displayMetrics.density
    var outline: GlassOutline? = null
        set(v) { field = v; invalidate() }
    var solved: GlassOutline.Rect? = null
        set(v) { field = v; invalidate() }
    /** Index of the dot the nudge buttons move. */
    var selected: Int = GlassOutline.TR
        private set
    var onChanged: (() -> Unit)? = null
    /** True while a dot is being dragged (the controls fade so they never hide the dot). */
    var onDragging: ((Boolean) -> Unit)? = null

    private val deadPaint = Paint().apply { color = 0x66FF3030; style = Paint.Style.FILL }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f * density
    }
    private val guidePaint = Paint().apply { color = 0x8000E5FF.toInt(); strokeWidth = 1f * density }
    private val rectFill = Paint().apply { color = 0x1A4ADE80; style = Paint.Style.FILL }
    private val rectStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4ADE80.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * density
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * density }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    private var dragging = -1
    private var offX = 0f
    private var offY = 0f

    /** Move the selected dot by (dx, dy) pixels. */
    fun nudge(dx: Float, dy: Float) {
        val o = outline ?: return
        val p = o.pts[selected]
        set(selected, p.x + dx, p.y + dy)
        onChanged?.invoke()
    }

    private fun set(i: Int, x: Float, y: Float) {
        val o = outline ?: return
        val pts = o.pts.toMutableList()
        pts[i] = GlassOutline.Pt(x.coerceIn(0f, width.toFloat()), y.coerceIn(0f, height.toFloat()))
        outline = GlassOutline(keepOrder(pts))
    }

    /**
     * Keeps each side a function: a side point stays strictly between its two corners along the
     * side (x for top/bottom, y for left/right), so the edges never fold back on themselves.
     */
    private fun keepOrder(p: MutableList<GlassOutline.Pt>): List<GlassOutline.Pt> {
        val g = 4f * density
        fun between(v: Float, a: Float, b: Float) =
            if (b - a > 2 * g) v.coerceIn(a + g, b - g) else (a + b) / 2f
        with(GlassOutline) {
            p[TM] = p[TM].copy(x = between(p[TM].x, p[TL].x, p[TR].x))
            p[BM] = p[BM].copy(x = between(p[BM].x, p[BL].x, p[BR].x))
            p[LM] = p[LM].copy(y = between(p[LM].y, p[TL].y, p[BL].y))
            p[RM] = p[RM].copy(y = between(p[RM].y, p[TR].y, p[BR].y))
        }
        return p
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val o = outline ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val near = o.pts.withIndex().minByOrNull { (_, p) -> hypot(p.x - e.x, p.y - e.y) }
                if (near == null || hypot(near.value.x - e.x, near.value.y - e.y) > GRAB_DP * density) return false
                dragging = near.index
                selected = near.index
                offX = near.value.x - e.x
                offY = near.value.y - e.y
                parent?.requestDisallowInterceptTouchEvent(true)
                onDragging?.invoke(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging < 0) return false
                set(dragging, e.x + offX, e.y + offY)
                onChanged?.invoke()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging >= 0) { dragging = -1; onDragging?.invoke(false); invalidate(); onChanged?.invoke() }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val o = outline ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        // Dim everything outside the outline: window rect minus the polygon (even-odd).
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, w, h, Path.Direction.CW)
        path.moveTo(o.pts[0].x, o.pts[0].y)
        for (i in 1 until 8) path.lineTo(o.pts[i].x, o.pts[i].y)
        path.close()
        c.drawPath(path, deadPaint)
        solved?.let { r ->
            c.drawRect(r.l, r.t, r.r, r.b, rectFill)
            c.drawRect(r.l, r.t, r.r, r.b, rectStroke)
        }
        path.reset()
        path.moveTo(o.pts[0].x, o.pts[0].y)
        for (i in 1 until 8) path.lineTo(o.pts[i].x, o.pts[i].y)
        path.close()
        c.drawPath(path, edgePaint)
        // Crosshair through the active dot, for lining it up against the bezel.
        val a = o.pts[selected]
        c.drawLine(0f, a.y, w, a.y, guidePaint)
        c.drawLine(a.x, 0f, a.x, h, guidePaint)
        for ((i, p) in o.pts.withIndex()) {
            val corner = i % 2 == 0
            val col = when {
                i == selected -> 0xFFFFD60A.toInt()
                corner -> 0xFFFFFFFF.toInt()
                else -> 0xFF00E5FF.toInt()
            }
            ring.color = col; dot.color = col
            c.drawCircle(p.x, p.y, (if (corner) 18f else 14f) * density, ring)
            c.drawCircle(p.x, p.y, 3f * density, dot)
        }
    }

    private companion object { const val GRAB_DP = 56f }
}
