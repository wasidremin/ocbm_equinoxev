package wasidremin.gmccpa.pane

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtBackdropTest {
    private fun lumaOf(p: Int) = ArtBackdrop.luma((p shr 16 and 0xFF).toFloat(), (p shr 8 and 0xFF).toFloat(), (p and 0xFF).toFloat())

    /** WCAG contrast of white text over an opaque sRGB pixel. */
    private fun whiteContrast(p: Int): Double {
        fun lin(v: Int): Double { val c = v / 255.0; return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4) }
        val l = 0.2126 * lin(p shr 16 and 0xFF) + 0.7152 * lin(p shr 8 and 0xFF) + 0.0722 * lin(p and 0xFF)
        return 1.05 / (l + 0.05)
    }

    @Test fun aWhiteCoverComesOutDarkEnoughForWhiteText() {
        val px = intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFE94A.toInt(), 0xFFFF9BC2.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt())
        ArtBackdrop.tone(px)
        for (p in px) {
            assertTrue("luma ${lumaOf(p)}", lumaOf(p) <= 73f)
            assertTrue("contrast ${whiteContrast(p)}", whiteContrast(p) >= 7.0)
        }
    }

    @Test fun aDarkCoverKeepsMostOfItsBrightness() {
        val px = intArrayOf(0xFF283C50.toInt())
        val before = lumaOf(px[0])
        ArtBackdrop.tone(px)
        assertTrue(lumaOf(px[0]) > before * 0.5f)
        assertTrue(lumaOf(px[0]) < before)
    }

    @Test fun toneKeepsTheHueAndOpacity() {
        val px = intArrayOf(0xFFE0115F.toInt(), 0xFF000000.toInt())
        ArtBackdrop.tone(px)
        val r = px[0] shr 16 and 0xFF; val g = px[0] shr 8 and 0xFF; val b = px[0] and 0xFF
        assertTrue(r > b && b > g)
        assertEquals(0xFF, px[0] ushr 24)
        assertEquals(0xFF000000.toInt(), px[1])
    }

    @Test fun brighterInNeverComesOutDarker() {
        var last = -1f
        for (l in 0..255) { val t = ArtBackdrop.toned(l.toFloat()); assertTrue(t >= last); last = t }
        assertTrue(last <= 72f)
    }

    @Test fun blurLeavesAFlatPictureAloneAndSpreadsAPoint() {
        val flat = IntArray(16 * 16) { 0xFF336699.toInt() }
        ArtBackdrop.blur(flat, 16, 16, 3, 2)
        assertTrue(flat.all { it == 0xFF336699.toInt() })

        val dot = IntArray(16 * 16) { 0xFF000000.toInt() }
        dot[8 * 16 + 8] = 0xFFFFFFFF.toInt()
        ArtBackdrop.blur(dot, 16, 16, 2, 2)
        assertTrue((dot[8 * 16 + 8] and 0xFF) < 60)
        assertTrue((dot[8 * 16 + 10] and 0xFF) > 0)
        assertTrue(dot.all { it ushr 24 == 0xFF })
    }
}
