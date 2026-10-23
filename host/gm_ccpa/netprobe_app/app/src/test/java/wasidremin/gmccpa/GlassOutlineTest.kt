package wasidremin.gmccpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The eight-point visible-glass outline and what it derives: picture rect and top-bar fit. */
class GlassOutlineTest {
    private val w = 2914f
    private val h = 1134f
    private fun pt(x: Float, y: Float) = GlassOutline.Pt(x, y)

    /** Equinox-like: top dips toward the right, right side swoops in near the bottom. */
    private val shaped = GlassOutline(listOf(
        pt(10f, 4f), pt(1400f, 20f), pt(2620f, 90f),   // TL, T, TR
        pt(2640f, 600f),                                  // R (bulges out mid-height)
        pt(2500f, 1120f), pt(1400f, 1128f), pt(20f, 1125f), // BR, B, BL
        pt(16f, 560f)))                                   // L

    @Test fun edgesInterpolateAlongEachSide() {
        assertEquals(20f, shaped.top(1400f), 0.01f)
        assertEquals(55f, shaped.top(2010f), 0.01f)          // halfway T -> TR
        assertEquals(4f, shaped.top(0f), 0.01f)              // flat beyond the corner
        assertEquals(2640f, shaped.right(600f), 0.01f)
        assertEquals(2570f, shaped.right(860f), 0.01f)       // halfway R -> BR
    }

    @Test fun containsRespectsEverySide() {
        assertTrue(shaped.contains(1400f, 500f))
        assertFalse(shaped.contains(2400f, 40f))             // above the dipping top
        assertFalse(shaped.contains(2600f, 1100f))           // outside the swoop
        assertFalse(shaped.contains(5f, 500f))               // left of the left edge
    }

    @Test fun boxOutlineGivesItsOwnRect() {
        val r = GlassOutline.box(100f, 170f, 2600f, 1100f).maxRect()
        assertNotNull(r)
        r!!
        assertEquals(100f, r.l, 3f); assertEquals(2600f, r.r, 3f)
        assertEquals(170f, r.t, 3f); assertEquals(1100f, r.b, 3f)
    }

    @Test fun maxRectStaysOnGlass() {
        val r = shaped.maxRect()
        assertNotNull(r)
        r!!
        for (x in listOf(r.l, (r.l + r.r) / 2f, r.r)) for (y in listOf(r.t, (r.t + r.b) / 2f, r.b))
            assertTrue("($x,$y) must be on glass", shaped.contains(x, y))
        // And it is big: most of the panel, not a timid inset.
        assertTrue("area ${r.width}x${r.height}", r.width > 2300f && r.height > 950f)
    }

    @Test fun nothingFitsInATinyOutline() {
        assertNull(GlassOutline.box(0f, 0f, 500f, 400f).maxRect())
    }

    @Test fun perMilleRoundTrips() {
        val back = GlassOutline.fromPerMille(shaped.toPerMille(w, h), w, h)!!
        for ((a, b) in shaped.pts.zip(back.pts)) {
            assertEquals(a.x, b.x, w / 1000f)
            assertEquals(a.y, b.y, h / 1000f)
        }
        assertNull(GlassOutline.fromPerMille(IntArray(15), w, h))
        assertNull(GlassOutline.fromPerMille(IntArray(16) { 1001 }, w, h))
    }

    @Test fun blockRightEdgeFollowsTheTopDip() {
        // A 40px-tall block at rows 120..160 fits right up to the right edge (the top there is 90).
        val edge = shaped.blockRightEdge(120f, 160f, 600f, 0f)
        assertEquals(shaped.rightLimit(120f, 160f), edge, 4f)
        // A block reaching up to row 60 must stop where the top edge is still above 60.
        val high = shaped.blockRightEdge(60f, 100f, 600f, 0f)
        assertTrue("edge $high", high < edge)
        assertTrue(shaped.topClearance(high - 600f, high) <= 60f)
    }

    @Test fun bottomJustifiedClusterReachesIntoTheCorner() {
        // The owner's 2026-10-23 car outline, estimated from the photo (picture 2334x1063 at 30,66):
        // the top dips to ~66 at TR, then the right side swoops out.
        val car = GlassOutline(listOf(pt(0f, 0f), pt(1200f, 30f), pt(2364f, 66f), pt(2600f, 560f),
            pt(2660f, 1134f), pt(1300f, 1134f), pt(30f, 1134f), pt(10f, 560f)))
        val barH = 174f
        val contentH = barH * 0.24f
        val margin = barH * 0.05f
        val natural = 700f
        val centred = car.blockRightEdge(barH * 0.47f - contentH / 2f, barH * 0.47f + contentH / 2f, natural, 0f)
        val bottom = car.blockRightEdge(barH - margin - contentH, barH - margin, natural, 0f)
        // Centred rows run into the dipping top short of the picture's edge; bottom rows clear it
        // and end past the picture, on the swooping right side.
        assertTrue("centred $centred", centred < 2364f)
        assertTrue("bottom $bottom", bottom > 2380f)
        assertEquals(car.rightLimit(barH - margin - contentH, barH - margin), bottom, 4f)
    }

    // ---- top-bar tiles against a traced top edge ----

    private fun tiles(topClear: ((Float, Float) -> Float)?) = DisplayGeometry.topBarTiles(
        174f, 0f, 9, 174f * 0.36f, 174f * 0.03f,
        gapFrac = 0.18f, maxFrac = 0.90f, breakAfter = 6, groupGapFrac = GmTopBarView.GROUP_GAP_FRAC,
        topClear = topClear)

    @Test fun flatTracedTopKeepsFullSizeTiles() {
        val flat = GlassOutline.box(0f, 0f, w, h)
        val row = tiles { a, b -> flat.topClearance(a, b) }
        assertEquals(174f * 0.90f, row.size, 0.5f)
    }

    @Test fun dippingTracedTopShrinksTilesUntilTheyClear() {
        // Top edge dips to y 60 by x 1700: tiles must come down below it.
        val dip = GlassOutline(listOf(pt(0f, 0f), pt(900f, 0f), pt(1700f, 60f), pt(1700f, 600f),
            pt(1700f, h), pt(850f, h), pt(0f, h), pt(0f, 600f)))
        val clear: (Float, Float) -> Float = { a, b -> dip.topClearance(a, b) }
        val row = tiles(clear)
        assertTrue(row.size < 174f * 0.90f)
        for (i in 0 until 9) {
            val l = row.tileLeft(i)
            assertTrue("tile $i top ${row.top} vs glass ${clear(l, l + row.size)}",
                row.top >= clear(l, l + row.size) + 174f * 0.03f - 0.01f)
        }
    }
}
