package wasidremin.gmccpa.nav

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint

/**
 * What more than one driver-display card draws: the bottom-right panel a docked card sits on
 * ([ClusterLayout.docked]), and the battery on arrival, which rides whichever card is showing
 * (the turn card's row, or the drive card when that is docked or the turn card is not this app's).
 * Main thread only: the paints are shared.
 */
internal object CardParts {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val plain = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val path = Path()

    /**
     * The docked panel behind [b]: it runs off the right and bottom edges of a [w] x [h] window with
     * the gauges' slanted inner edge ([ClusterLayout.DOCK_SLANT]), in their black.
     */
    fun dockPanel(canvas: Canvas, b: ClusterLayout.Box, w: Float, h: Float) {
        val x0 = b.left
        val y0 = b.top
        val rad = b.height * 0.14f
        path.rewind()
        path.moveTo(x0 + rad, y0)
        path.lineTo(w, y0); path.lineTo(w, h)
        path.lineTo(x0 - ClusterLayout.DOCK_SLANT * (h - y0), h)
        path.lineTo(x0 - ClusterLayout.DOCK_SLANT * rad, y0 + rad)
        path.quadTo(x0, y0, x0 + rad, y0)
        path.close()
        fill.color = ClusterLayout.GAUGE.card
        canvas.drawPath(path, fill)
    }

    /**
     * The battery on arrival: a small battery filled to the figure, then "62% on arrival" (the figure
     * alone when [maxWidth] is short), amber under 10% and "Charge needed" at or below zero. With
     * [canvas] null it only measures. On a panel ([onPanel]) it has no tab of its own. [h] is the
     * height of the card it belongs to.
     */
    fun arrival(canvas: Canvas?, a: DriveCard.Arrival, left: Float, top: Float, maxWidth: Float, h: Float,
                pal: ClusterLayout.Palette, onPanel: Boolean, night: Boolean): Float {
        val th = h * 0.30f
        val pad = h * 0.10f
        val bw = h * 0.27f
        val bh = h * 0.14f
        plain.textSize = h * 0.15f
        val figure = if (a.short) "Charge needed" else "${a.pct}%"
        val full = if (a.short) figure else "$figure on arrival"
        val text = if (pad * 2.6f + bw + plain.measureText(full) <= maxWidth) full else figure
        val w = pad * 2.6f + bw + plain.measureText(text)
        if (canvas == null) return w
        if (!onPanel) {
            fill.color = pal.card
            r.set(left, top, left + w, top + th)
            canvas.drawRoundRect(r, th * 0.42f, th * 0.42f, fill)
        }
        val level = if (a.low) (if (night || onPanel) ClusterLayout.AMBER_NIGHT else ClusterLayout.AMBER)
            else (if (night || onPanel) ClusterLayout.GREEN_NIGHT else ClusterLayout.GREEN)
        val bx = left + pad
        val by = top + (th - bh) / 2f
        val nub = h * 0.03f
        stroke.color = pal.dim; stroke.strokeWidth = h * 0.016f
        r.set(bx, by, bx + bw - nub, by + bh)
        canvas.drawRoundRect(r, h * 0.025f, h * 0.025f, stroke)
        fill.color = pal.dim
        r.set(bx + bw - nub, by + bh * 0.32f, bx + bw, by + bh * 0.68f)
        canvas.drawRect(r, fill)
        val inner = h * 0.022f
        fill.color = level
        r.set(bx + inner, by + inner, bx + inner + (bw - nub - inner * 2) * (a.pct / 100f).coerceIn(0.04f, 1f), by + bh - inner)
        canvas.drawRect(r, fill)
        plain.color = if (a.low) level else pal.text
        canvas.drawText(text, bx + bw + pad * 0.6f, top + th / 2f + plain.textSize * 0.35f, plain)
        return w
    }
}
