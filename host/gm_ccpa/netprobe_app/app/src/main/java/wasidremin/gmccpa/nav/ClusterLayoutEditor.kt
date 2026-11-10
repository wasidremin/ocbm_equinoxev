package wasidremin.gmccpa.nav

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import wasidremin.gmccpa.ocbm.PixelSize
import kotlin.math.roundToInt

/**
 * Settings ▸ Driver display: a miniature of the driver display on which each card is dragged to its
 * place ([ClusterLayout]). The real cards follow on the driver display as the finger moves, and show
 * samples there for [DriverDisplay.PREVIEW_MS]. [onPick] reports the card last touched, which is the
 * one the size buttons act on.
 */
class ClusterLayoutEditor(ctx: Context, private val onPick: (ClusterLayout.Slot) -> Unit) : View(ctx) {
    private val size: PixelSize get() = DriverDisplay.size ?: PixelSize(2000, 810)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val r = RectF()
    private val gauges = Path()
    var picked = ClusterLayout.Slot.TURN
        private set
    private var grabX = 0f
    private var grabY = 0f
    private var dragging = false

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, w * size.height / size.width)
    }

    private fun shown(slot: ClusterLayout.Slot) = when (slot) {
        ClusterLayout.Slot.TURN -> DriverDisplay.turnCard
        ClusterLayout.Slot.MEDIA -> ClusterLayout.media || ClusterLayout.call
    }

    /** [slot]'s card and what hangs under it, in this view's pixels. */
    private fun footprint(slot: ClusterLayout.Slot, out: RectF) {
        val b = ClusterLayout.rect(width, height, slot, ClusterLayout.place(context, slot))
        out.set(b.left, b.top, b.left + b.width, b.top + b.height * slot.stack)
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val w = width.toFloat(); val h = height.toFloat()
        val u = h / 100f
        fill.color = Color.rgb(0x1B, 0x24, 0x30)
        r.set(0f, 0f, w, h)
        canvas.drawRoundRect(r, u * 5, u * 5, fill)
        // Where the Equinox draws its gauges over the map: the bottom third on the left, sloping away.
        gauges.rewind()
        gauges.moveTo(0f, h * 0.66f); gauges.lineTo(w * 0.30f, h * 0.66f); gauges.lineTo(w * 0.40f, h); gauges.lineTo(0f, h); gauges.close()
        fill.color = Color.argb(150, 0, 0, 0)
        canvas.drawPath(gauges, fill)
        text.color = Color.argb(150, 255, 255, 255); text.textSize = u * 6.5f; text.isFakeBoldText = false
        canvas.drawText("gauges", w * 0.15f, h * 0.86f, text)
        // The directions area CarPlay keeps its own arrival strip and speed limit inside.
        DriverDisplay.safeArea(PixelSize(width and 1.inv(), height and 1.inv()), DriverDisplay.margins(context))?.let { a ->
            line.color = Color.rgb(0x4C, 0xC2, 0xFF); line.strokeWidth = u * 0.7f
            line.pathEffect = DashPathEffect(floatArrayOf(u * 3, u * 3), 0f)
            r.set(a.x.toFloat(), a.y.toFloat(), a.right.toFloat(), (a.y + a.height).toFloat())
            canvas.drawRect(r, line)
            text.color = Color.argb(170, 0x4C, 0xC2, 0xFF)
            canvas.drawText("CarPlay's map and its own arrival strip", r.centerX(), r.bottom - u * 4, text)
        }
        line.pathEffect = null
        for (slot in ClusterLayout.Slot.values()) {
            if (!shown(slot)) continue
            footprint(slot, r)
            val on = slot == picked
            fill.color = if (on) Color.argb(120, 0xFF, 0xB3, 0x40) else Color.argb(70, 255, 255, 255)
            canvas.drawRoundRect(r, u * 3, u * 3, fill)
            line.color = if (on) Color.rgb(0xFF, 0xB3, 0x40) else Color.argb(170, 255, 255, 255)
            line.strokeWidth = if (on) u * 1.2f else u * 0.7f
            canvas.drawRoundRect(r, u * 3, u * 3, line)
            text.color = Color.WHITE; text.isFakeBoldText = true
            text.textSize = minOf(u * 8f, r.height() * 0.4f)
            canvas.drawText(slot.label, r.centerX(), r.centerY() + text.textSize * 0.35f, text)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = ClusterLayout.Slot.values().filter { shown(it) }.firstOrNull { footprint(it, r); r.contains(e.x, e.y) }
                    ?: return false
                picked = hit
                footprint(hit, r)
                grabX = e.x - r.left; grabY = e.y - r.top
                dragging = true
                // The page scrolls; a card being dragged must not.
                parent?.requestDisallowInterceptTouchEvent(true)
                onPick(hit)
                DriverDisplay.preview()
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val old = ClusterLayout.place(context, picked)
                // Through rect(), so the saved corner is where the card really ends up.
                val want = ClusterLayout.Place(((e.x - grabX) / width * 100).roundToInt(), ((e.y - grabY) / height * 100).roundToInt(), old.scale)
                val b = ClusterLayout.rect(width, height, picked, want)
                val p = ClusterLayout.Place((b.left / width * 100).roundToInt(), (b.top / height * 100).roundToInt(), old.scale)
                if (p != old) { ClusterLayout.setPlace(context, picked, p); invalidate() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}
