package wasidremin.gmccpa

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View

/**
 * Full-window finger painting for edge calibration. Strokes are kept in window pixels and
 * handed to [ScribbleSolver]; this view only collects and draws them.
 *
 * Paint the DEAD glass — the slopes, swoops and cutouts where content disappears — tracing
 * each edge end to end. Corners belong to both edges. A few missed bands are fine (the seed
 * covers untraced bands); a few stray dots are ignored (bands need 3+ points).
 */
class ScribbleView(ctx: Context) : View(ctx) {
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 14f * ctx.resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val faintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * ctx.resources.displayMetrics.density
    }
    private val solvedFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1A4ADE80
        style = Paint.Style.FILL
    }
    private val solvedStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4ADE80.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f * ctx.resources.displayMetrics.density
    }
    private val paths = mutableListOf<Path>()
    private val strokes = mutableListOf<MutableList<ScribbleSolver.Pt>>()
    private var current: Path? = null
    private var currentPts: MutableList<ScribbleSolver.Pt>? = null
    var solvedRect: ScribbleSolver.Result? = null
        set(v) { field = v; invalidate() }

    var onChanged: (() -> Unit)? = null

    fun strokeLists(): List<List<ScribbleSolver.Pt>> =
        if (currentPts != null) strokes + listOf(currentPts!!.toList()) else strokes.toList()

    fun clear() {
        paths.clear()
        strokes.clear()
        current = null
        currentPts = null
        solvedRect = null
        invalidate()
        onChanged?.invoke()
    }

    val strokeCount: Int get() = strokes.size + if (current != null) 1 else 0

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                current = Path().apply { moveTo(e.x, e.y) }
                currentPts = mutableListOf(ScribbleSolver.Pt(e.x, e.y))
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val p = current ?: return true
                val pts = currentPts ?: return true
                for (i in 0 until e.historySize) {
                    p.lineTo(e.getHistoricalX(i), e.getHistoricalY(i))
                    pts.add(ScribbleSolver.Pt(e.getHistoricalX(i), e.getHistoricalY(i)))
                }
                p.lineTo(e.x, e.y)
                pts.add(ScribbleSolver.Pt(e.x, e.y))
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current?.let { paths.add(it) }
                currentPts?.let { strokes.add(it) }
                current = null
                currentPts = null
                invalidate()
                onChanged?.invoke()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Faint edge-zone guides: the solver's zones, so a stroke in the middle third that
        // votes for no edge is visibly off-task before it happens.
        val w = width.toFloat()
        val h = height.toFloat()
        if (w > 0 && h > 0) {
            canvas.drawLine(0f, h * 0.45f, w, h * 0.45f, faintPaint)
            canvas.drawLine(0f, h * 0.55f, w, h * 0.55f, faintPaint)
            canvas.drawLine(w * 0.40f, 0f, w * 0.40f, h, faintPaint)
            canvas.drawLine(w * 0.60f, 0f, w * 0.60f, h, faintPaint)
        }
        solvedRect?.let { r ->
            if (r.r > r.l && r.b > r.t) {
                canvas.drawRect(r.l, r.t, r.r, r.b, solvedFillPaint)
                canvas.drawRect(r.l, r.t, r.r, r.b, solvedStrokePaint)
            }
        }
        for (p in paths) canvas.drawPath(p, strokePaint)
        current?.let { canvas.drawPath(it, strokePaint) }
    }
}
