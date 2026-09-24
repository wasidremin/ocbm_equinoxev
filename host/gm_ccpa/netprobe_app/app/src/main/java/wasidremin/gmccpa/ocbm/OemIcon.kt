package wasidremin.gmccpa.ocbm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Base64

/**
 * The tile iOS puts on the CarPlay home screen. Tapping it comes back as `requestUI`.
 *
 * All three sizes are required. A single size renders as a label with no artwork, which is the
 * blank tile. The mark is drawn here, not taken from the launcher icon: an adaptive icon's
 * background layer does not fit in one subscribe frame, and a missing image is the white square.
 */
object OemIcon {
    val SIZES = intArrayOf(120, 180, 256)

    data class Image(val width: Int, val height: Int, val base64: String)

    /** Dark rounded tile with a light gear. Stable across process starts; no launcher asset. */
    fun gear(): List<Image> {
        val out = ArrayList<Image>(SIZES.size)
        for (px in SIZES) {
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            try {
                drawGear(Canvas(bmp), px)
                val png = java.io.ByteArrayOutputStream(px * px / 2)
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, png)) return emptyList()
                out.add(Image(px, px, Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP)))
            } finally {
                bmp.recycle()
            }
        }
        return out
    }

    private fun drawGear(canvas: Canvas, px: Int) {
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8EEF2.toInt(); style = Paint.Style.FILL }
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A2428.toInt(); style = Paint.Style.FILL }
        canvas.drawColor(0xFF1A2428.toInt())
        val c = px / 2f
        val outer = px * 0.34f
        val inner = px * 0.22f
        val tooth = px * 0.08f
        canvas.drawCircle(c, c, outer, ink)
        var i = 0
        while (i < 8) {
            canvas.save()
            canvas.rotate(i * 45f, c, c)
            canvas.drawRoundRect(c - tooth, c - outer - tooth, c + tooth, c - inner, tooth, tooth, ink)
            canvas.restore()
            i++
        }
        canvas.drawCircle(c, c, px * 0.12f, hole)
    }
}
