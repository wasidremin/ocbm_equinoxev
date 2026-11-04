package wasidremin.gmccpa.match

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScreenModel.mapsCard] on synthetic 1/4-scale frames. The rule was set on the owner's own iOS 27.2
 * captures of 2026-11-03/04, which show where the car was and so stay local; these frames copy
 * what mattered in them. The card sat at x 38–196, y 4–57 of a 617x240 frame at 0.951x, with a
 * flat background of 0.10 at dusk and 0.24 in daylight, over a map of 0.2–0.35 or 0.8–0.9.
 */
class MapsCardTest {
    private val w = 617
    private val h = 240

    /** A map: deterministic noise around [base] with a few road lines, as uneven as the real ones. */
    private fun map(base: Float, spread: Float): FloatArray {
        var seed = 12345L
        return FloatArray(w * h) { i ->
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            val noise = ((seed ushr 33) % 1000) / 1000f - 0.5f
            val x = i % w
            val y = i / w
            val road = (x + 2 * y) % 97 < 3 || (3 * x - y) % 131 < 2
            (base + noise * spread + if (road) 0.25f else 0f).coerceIn(0f, 1f)
        }
    }

    /** Maps' turn card: flat [tone] with white lines of text, at the 0.951x spot, moved by [dx]/[dy]. */
    private fun card(px: FloatArray, tone: Float, dx: Int = 0, dy: Int = 0, ui: Float = 0.951f) {
        val s = ui / 0.951f
        val x0 = (38 * s).toInt() + dx
        val x1 = (197 * s).toInt() + dx
        val y0 = (4 * s).toInt() + dy
        val y1 = (58 * s).toInt() + dy
        for (y in y0 until y1) for (x in x0 until x1) px[y * w + x] = tone
        for (line in 0 until 3) {
            val ty = y0 + (12 + 14 * line) * s
            for (y in ty.toInt() until (ty + 6 * s).toInt()) for (x in x0 + 24 until x0 + (24 + 70 * s / (line + 1)).toInt()) {
                px[y * w + x] = 0.95f
            }
        }
    }

    private fun read(px: FloatArray, ui: Float = 0.951f) =
        ScreenModel.Gray(w, h, px).let { ScreenModel.mapsCard(it, ScreenModel.geometry(it, ui)) }

    @Test fun theCardIsFoundAtDuskAndInDaylight() {
        assertTrue(read(map(0.28f, 0.15f).also { card(it, 0.10f) }).present)
        assertTrue(read(map(0.85f, 0.10f).also { card(it, 0.24f) }).present)
    }

    @Test fun theMapAloneIsNotTheCard() {
        // The Dashboard: only map in the top left, its own turn card elsewhere.
        assertFalse(read(map(0.28f, 0.15f)).present)
        assertFalse(read(map(0.85f, 0.10f)).present)
    }

    @Test fun aDarkAppIsDarkAllTheWayAcross() {
        val px = FloatArray(w * h) { 0.08f }
        for (y in 20 until 26) for (x in 60 until 160) px[y * w + x] = 0.9f
        assertFalse(read(px).present)
    }

    @Test fun aLightPanelIsNotTheTurnCard() {
        // Route preview and the apps' own light panels.
        assertFalse(read(map(0.85f, 0.10f).also { card(it, 0.96f) }).present)
    }

    @Test fun aSlightlyWrongScaleOrPositionStillFindsIt() {
        assertTrue(read(map(0.28f, 0.15f).also { card(it, 0.10f, dx = 2, dy = -2) }).present)
        assertTrue(read(map(0.28f, 0.15f).also { card(it, 0.10f) }, ui = 0.93f).present)
        assertTrue(read(map(0.85f, 0.10f).also { card(it, 0.24f, ui = 1.276f) }, ui = 1.276f).present)
    }
}
