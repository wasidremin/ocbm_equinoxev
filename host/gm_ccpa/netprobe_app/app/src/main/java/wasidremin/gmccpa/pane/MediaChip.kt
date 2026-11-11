package wasidremin.gmccpa.pane

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import wasidremin.gmccpa.nav.ClusterLayout

/**
 * The small round now-playing mark: the cover as a disc inside a ring that fills as the song plays.
 * The same mark the driver display's now-playing card folds to ([wasidremin.gmccpa.nav.MediaCard]),
 * in the same proportions and its night colours, so the two read as one thing.
 */
class MediaChip {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val art = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val note = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }
    private val r = RectF()
    private val clip = Path()

    /** A chip [d] across centred on ([cx], [cy]). [progress] null draws the ring empty. */
    fun draw(c: Canvas, cx: Float, cy: Float, d: Float, cover: Bitmap?, progress: Float?) {
        val pal = ClusterLayout.palette(true)
        fill.color = pal.card
        c.drawCircle(cx, cy, d / 2f, fill)

        val side = d * 0.80f
        r.set(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
        clip.rewind(); clip.addOval(r, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        if (cover != null) c.drawBitmap(cover, null, r, art)
        else {
            fill.color = pal.track
            c.drawRect(r, fill)
            note.color = pal.dim; note.textSize = side * 0.5f
            c.drawText("♪", cx - note.measureText("♪") / 2f, cy + note.textSize * 0.35f, note)
        }
        c.restore()

        ring.strokeWidth = d * 0.055f
        val o = ring.strokeWidth
        r.set(cx - d / 2f + o, cy - d / 2f + o, cx + d / 2f - o, cy + d / 2f - o)
        ring.color = pal.track
        c.drawOval(r, ring)
        if (progress != null) {
            ring.color = pal.accent
            c.drawArc(r, -90f, 360f * progress.coerceIn(0f, 1f), false, ring)
        }
    }
}
