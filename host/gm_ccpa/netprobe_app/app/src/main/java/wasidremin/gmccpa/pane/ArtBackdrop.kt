package wasidremin.gmccpa.pane

import android.graphics.Bitmap

/**
 * The media pane's backdrop: the cover blown up, blurred and dimmed, as CarPlay's own Now Playing
 * draws it. The picture is made small ([SIZE] square), blurred there, and stretched by the pane, so
 * it costs nothing per frame and works on every Android this app runs on.
 *
 * Text is drawn in white over it, so [tone] caps how bright any part of it can be: a white cover
 * comes out a dark grey, a dark cover keeps most of what it has, and colour is pushed up a little
 * first so the dimming does not leave it grey.
 */
object ArtBackdrop {
    const val SIZE = 64
    private const val BLUR_RADIUS = 7
    private const val BLUR_PASSES = 3
    private const val SATURATION = 1.35f
    /** Brightness (0..255) kept from dark covers, and the ceiling a bright one approaches. */
    private const val GAIN = 0.70f
    private const val CEILING = 120f

    fun make(cover: Bitmap): Bitmap {
        val small = Bitmap.createScaledBitmap(cover, SIZE, SIZE, true)
        val px = IntArray(SIZE * SIZE)
        small.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        blur(px, SIZE, SIZE, BLUR_RADIUS, BLUR_PASSES)
        tone(px)
        return Bitmap.createBitmap(px, SIZE, SIZE, Bitmap.Config.ARGB_8888)
    }

    /** Pure: the brightness (0..255) [tone] gives a pixel of brightness [luma]. Never above 72. */
    fun toned(luma: Float): Float = GAIN * luma / (1f + GAIN * luma / CEILING)

    /** Pure, in place: opaque ARGB pixels with the colour raised and the brightness capped. */
    fun tone(px: IntArray) {
        for (i in px.indices) {
            val p = px[i]
            var r = (p shr 16 and 0xFF).toFloat()
            var g = (p shr 8 and 0xFF).toFloat()
            var b = (p and 0xFF).toFloat()
            val l = luma(r, g, b)
            r = (l + (r - l) * SATURATION).coerceIn(0f, 255f)
            g = (l + (g - l) * SATURATION).coerceIn(0f, 255f)
            b = (l + (b - l) * SATURATION).coerceIn(0f, 255f)
            val l2 = luma(r, g, b)
            val k = if (l2 < 1f) 0f else toned(l2) / l2
            px[i] = (0xFF shl 24) or ((r * k).toInt() shl 16) or ((g * k).toInt() shl 8) or (b * k).toInt()
        }
    }

    fun luma(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

    /** Pure, in place: [passes] box blurs of [radius], which together are close to a Gaussian. */
    fun blur(px: IntArray, w: Int, h: Int, radius: Int, passes: Int) {
        val tmp = IntArray(px.size)
        repeat(passes) {
            box(px, tmp, w, h, radius, across = true)
            box(tmp, px, w, h, radius, across = false)
        }
    }

    private fun box(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, across: Boolean) {
        val lines = if (across) h else w
        val len = if (across) w else h
        val n = radius * 2 + 1
        for (line in 0 until lines) {
            var r = 0; var g = 0; var b = 0
            fun at(i: Int): Int {
                val c = i.coerceIn(0, len - 1)
                return if (across) src[line * w + c] else src[c * w + line]
            }
            for (i in -radius..radius) { val p = at(i); r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF }
            for (i in 0 until len) {
                val out = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                if (across) dst[line * w + i] = out else dst[i * w + line] = out
                val add = at(i + radius + 1); val sub = at(i - radius)
                r += (add shr 16 and 0xFF) - (sub shr 16 and 0xFF)
                g += (add shr 8 and 0xFF) - (sub shr 8 and 0xFF)
                b += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }
}
