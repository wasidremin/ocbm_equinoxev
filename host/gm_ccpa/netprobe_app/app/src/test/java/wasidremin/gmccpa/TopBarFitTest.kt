package wasidremin.gmccpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Equinox EV panel fitment: the panel is not a rectangle (top slopes down left-to-right,
 * right side cut away; vehicle reports safe insets 0/167/285/0 on a 2914x1134 window).
 * The 2026-10-18 full-window capture proved the bar's clock + temperature — the rightmost
 * items — were drawn at x 2700-2850, i.e. on missing glass.
 *
 * The 2026-10-15 geometry log gives the exact glass line: cutout rects
 * `Rect(0, 0 - 2629, 167)` + `Rect(2629, 0 - 2932, 1134)`, so the top wedge is a straight
 * slope m = 167/2629 and the bar (y 0..168) is visible only below it. Centering content in
 * the rect put the whole right cluster on missing glass (slope eats y 0..127-161 at
 * x 2000-2540); bar content therefore hangs from slope-clearing tops near the bottom.
 */
class TopBarFitTest {
    // Vehicle constants shared by the slope tests below.
    private val w = 2914f
    private val h = 168f
    private val inset = 285
    private val slopeM = 167f / 2629f          // 0.06352
    private val margin = h * 0.05f             // 8.4
    private val contentH = h * 0.28f           // 47.04: tallest cluster item (status icons)
    private val leftEnd = h * 6.67f            // 1120.56: last icon centre + half pitch

    @Test fun zeroInsetKeepsIndentedRightAlignment() {
        // 0.55 x bar height of deliberate indent from the side, like the car's own bar.
        assertEquals(2914f - 168f * 0.55f, DisplayGeometry.clusterRightEdge(2914f, 168f, 0), 0.001f)
    }

    @Test fun measuredCutoutShiftsClusterOntoGlass() {
        val edge = DisplayGeometry.clusterRightEdge(2914f, 168f, 285)
        assertEquals(2914f - 168f * 0.55f - 285f, edge, 0.001f)
        // Rightmost glyph ends at the edge: 2537 < 2629, clear of the cutout.
        assertTrue(edge < 2629f)
    }

    @Test fun leftBlockEndMatchesSevenIconLoop() {
        // First centre 0.95h, 7 icons at 0.88h pitch, plus half a pitch past the last.
        assertEquals(168f * 6.67f, DisplayGeometry.leftBlockEnd(168f), 0.01f)
    }

    @Test fun fittingClusterDrawsAtFullSize() {
        assertEquals(1f, DisplayGeometry.clusterFitScale(1969f, 900f), 0.001f)
    }

    @Test fun narrowSlotShrinksClusterToFitExactly() {
        // Narrow window + large inset: the slot holds only half the natural width.
        val scale = DisplayGeometry.clusterFitScale(205f, 410f)
        assertEquals(0.5f, scale, 0.001f)
        // Landing check: scaled cluster ends exactly at the slot edges.
        assertEquals(205f, 410f * scale, 0.01f)
    }

    @Test fun zeroNaturalWidthNeverDividesByZero() {
        assertEquals(1f, DisplayGeometry.clusterFitScale(100f, 0f), 0.001f)
    }

    @Test fun noRoomHidesClusterInsteadOfOverlapping() {
        assertEquals(0f, DisplayGeometry.clusterFitScale(0f, 410f), 0.001f)
        assertEquals(0f, DisplayGeometry.clusterFitScale(-50f, 410f), 0.001f)
    }

    @Test fun vehicleGeometryFitsAtFullSize() {
        // 2914x1134 window, 94px bar, 285 cutout: slot holds the cluster with room to spare.
        val h = 94f
        val slot = DisplayGeometry.clusterRightEdge(2914f, h, 285) - DisplayGeometry.leftBlockEnd(h)
        assertTrue(slot > 1500f)
        assertEquals(1f, DisplayGeometry.clusterFitScale(slot, 510f), 0.001f)
    }

    @Test fun negativeInsetClampsToHistorical() {
        assertEquals(DisplayGeometry.clusterRightEdge(2914f, 168f, 0),
            DisplayGeometry.clusterRightEdge(2914f, 168f, -50), 0.001f)
    }

    @Test fun nearestPresetHitsExactStops() {
        val p = intArrayOf(0, 48, 96, 160, 240, 320)
        assertEquals(0, DisplayGeometry.nearestPresetIndex(p, 0))
        assertEquals(3, DisplayGeometry.nearestPresetIndex(p, 160))
        assertEquals(5, DisplayGeometry.nearestPresetIndex(p, 320))
    }

    @Test fun retiredPresetValuesMapToNearestStop() {
        val p = intArrayOf(0, 48, 96, 160, 240, 320)
        // 24/72 predate the extended list; ties keep the lower stop (less inset, not more).
        assertEquals(0, DisplayGeometry.nearestPresetIndex(p, 24))
        assertEquals(1, DisplayGeometry.nearestPresetIndex(p, 72))
        assertEquals(2, DisplayGeometry.nearestPresetIndex(p, 100))
    }

    @Test fun outOfRangeValuesClampToEnds() {
        val p = intArrayOf(0, 48, 96, 160, 240, 320)
        assertEquals(0, DisplayGeometry.nearestPresetIndex(p, -10))
        assertEquals(5, DisplayGeometry.nearestPresetIndex(p, 9999))
        assertEquals(0, DisplayGeometry.nearestPresetIndex(intArrayOf(), 48))
    }

    @Test fun wedgeSlopeMatchesMeasuredCutout() {
        assertEquals(167f / 2629f, DisplayGeometry.wedgeSlopeM(2629, 167), 1e-6f)
    }

    @Test fun degenerateWedgeMeansFlatPanel() {
        assertEquals(0f, DisplayGeometry.wedgeSlopeM(0, 167), 0f)
        assertEquals(0f, DisplayGeometry.wedgeSlopeM(2629, 0), 0f)
        assertEquals(0f, DisplayGeometry.wedgeSlopeM(-5, 10), 0f)
    }

    @Test fun flatPanelNeedsNoShift() {
        assertEquals(0f, DisplayGeometry.levelShift(40f, 1046f, 0f, margin), 0f)
        // Centered content that already clears keeps its position.
        assertEquals(0f, DisplayGeometry.levelShift(80f, 500f, slopeM, margin), 0f)
    }

    @Test fun leftRowClearsSlopeAtLastIcon() {
        // Icon box top when centered: cy - icon/2 = (0.47 - 0.23) * h.
        val centeredTop = h * 0.24f
        // Last icon centre: (0.95 + 6 * 0.88) * h.
        val shift = DisplayGeometry.levelShift(centeredTop, h * 6.23f, slopeM, margin)
        assertEquals(34.6f, shift, 0.5f)
        // Every icon's top then sits on glass with margin to spare.
        val top = centeredTop + shift
        for (i in 0..6) {
            val x = h * (0.95f + 0.88f * i)
            assertTrue("icon $i top $top under slope ${slopeM * x}", top - slopeM * x >= margin - 0.5f)
        }
    }

    @Test fun flatPanelKeepsHistoricalEdge() {
        assertEquals(DisplayGeometry.clusterRightEdge(w, h, inset),
            DisplayGeometry.clusterRightEdgeOnGlass(w, h, inset, 0f, contentH, margin), 0.001f)
        assertEquals(DisplayGeometry.clusterRightEdge(w, h, inset),
            DisplayGeometry.clusterRightEdgeOnGlass(w, h, inset, slopeM, 0f, margin), 0.001f)
    }

    @Test fun vehicleEdgeSolvesGlassHeight() {
        // (168 - 2*8.4 - 47.04) / 0.06352: the furthest-right x with ~50px of glass.
        val edge = DisplayGeometry.clusterRightEdgeOnGlass(w, h, inset, slopeM, contentH, margin)
        assertEquals(1640f, edge, 3f)
        // Strictly inside both the historical edge and the glass end.
        assertTrue(edge < DisplayGeometry.clusterRightEdge(w, h, inset))
        assertTrue(edge < w - inset)
    }

    @Test fun absurdContentHidesInsteadOfOverlapping() {
        val edge = DisplayGeometry.clusterRightEdgeOnGlass(w, h, inset, slopeM, 500f, margin)
        assertTrue(edge < leftEnd)
    }

    @Test fun vehicleClusterSpanIsFullyOnGlass() {
        val edge = DisplayGeometry.clusterRightEdgeOnGlass(w, h, inset, slopeM, contentH, margin)
        // Conservative worst case: every field populated, long 12h clock + temperature.
        val natural = 700f
        val slot = edge - leftEnd
        assertTrue("slot $slot must hold a shrunken cluster", slot > 0f)
        val fit = DisplayGeometry.clusterFitScale(slot, natural)
        assertTrue(fit < 1f && fit > 0.5f)
        // Drawn span starts where the left block ends and ends at the solved edge.
        val drawnLeft = edge - slot
        assertEquals(leftEnd, drawnLeft, 0.5f)
        // The anchored top clears the slope at every point of the span (binds at the edge).
        val top = slopeM * edge + margin
        for (k in 0..10) {
            val x = drawnLeft + (edge - drawnLeft) * k / 10f
            assertTrue("x=$x top=$top slope=${slopeM * x}", top >= slopeM * x)
        }
        // The block bottom stays inside the bar.
        assertTrue(top + contentH * fit <= h - margin + 0.5f)
    }

    @Test fun justifiedLayoutPinsRightEdgeToUsableGlass() {
        // Roomy flat panel (no slope): right cluster at full size, right-justified to the
        // usable edge, left block untouched, middle gap absorbs the rest.
        val leftEnd = DisplayGeometry.leftBlockEnd(h)
        val natural = 500f
        val layout = DisplayGeometry.topBarLayout(w, h, inset, 0f, contentH, margin, leftEnd, natural)
        assertEquals(DisplayGeometry.clusterRightEdgeOnGlass(w, h, inset, 0f, contentH, margin),
            layout.rightEdge, 0.001f)
        assertEquals(1f, layout.rightScale, 0.001f)
        assertEquals(layout.rightEdge - natural, layout.rightStart, 0.01f)
        assertTrue("gap ${layout.gap} must hold half a bar", layout.gap >= h * 0.5f - 0.5f)
        assertEquals(layout.rightStart - leftEnd, layout.gap, 0.01f)
    }

    @Test fun crowdedLayoutShrinksRightToProtectTheGap() {
        // Vehicle geometry with a wide cluster: the old code let the clusters touch; the
        // justified solver shrinks the right side to keep half a bar of daylight.
        val leftEnd = DisplayGeometry.leftBlockEnd(h)
        val layout = DisplayGeometry.topBarLayout(w, h, inset, slopeM, contentH, margin, leftEnd, 700f)
        assertTrue(layout.rightScale < 1f && layout.rightScale > 0f)
        assertEquals(h * 0.5f, layout.gap, 1f)
        assertEquals(layout.rightEdge - 700f * layout.rightScale, layout.rightStart, 0.5f)
    }

    @Test fun noRoomHidesRightInsteadOfOverlapping() {
        val leftEnd = DisplayGeometry.leftBlockEnd(h)
        val layout = DisplayGeometry.topBarLayout(w, h, inset, slopeM, contentH, margin, leftEnd, 700f,
            minGap = 5000f)
        assertEquals(0f, layout.rightScale, 0.001f)
    }

    @Test fun settingsCeilingsCoverTheMeasuredCutout() {
        // safeInsetRight = 285 on the vehicle. Both the phone-side margin stops and the
        // surface-side dp slider must reach past it or the right edge stays under glass.
        assertTrue(DisplayPrefs.SAFE_PRESETS.max() >= 285)
        assertTrue(DisplayPrefs.RIGHT_DP_MAX >= 285)
    }
}
