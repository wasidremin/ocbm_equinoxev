package wasidremin.gmccpa.pane

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import wasidremin.gmccpa.EqTheme

/**
 * The glass right of the CarPlay picture, under the top bar: a tap shows or hides the media pane
 * ([SplitPane]). On the Equinox the picture ends where the narrowest row of glass does (x 2630 of
 * 2914), so this strip is the swooping right side, which reaches further on some rows than others.
 *
 * It draws only a faint handle, at the row where the glass reaches furthest ([handleAt], in this
 * view's coordinates); with no traced outline the handle sits mid-strip.
 */
class PaneStrip(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var hx = -1f
    private var hy = -1f

    /** Lit while the pane is up, faint while it is hidden. */
    var lit: Boolean = false
        set(v) { if (v != field) { field = v; invalidate() } }

    init {
        isClickable = true
        contentDescription = "Show or hide the media pane"
    }

    /** The handle's centre, or a negative x for none (no glass wide enough to hold it). */
    fun handleAt(x: Float, y: Float) {
        if (x == hx && y == hy) return
        hx = x
        hy = y
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (hx < 0f) return
        val w = EqTheme.px(6f).toFloat()
        val h = EqTheme.px(72f).toFloat()
        paint.color = if (lit) 0x66FFFFFF else 0x33FFFFFF
        r.set(hx - w / 2f, hy - h / 2f, hx + w / 2f, hy + h / 2f)
        c.drawRoundRect(r, w / 2f, w / 2f, paint)
    }

    companion object {
        /** Narrower glass than this holds no handle, though the strip still takes a tap. */
        fun minGlassPx(): Int = EqTheme.px(16f)
    }
}
