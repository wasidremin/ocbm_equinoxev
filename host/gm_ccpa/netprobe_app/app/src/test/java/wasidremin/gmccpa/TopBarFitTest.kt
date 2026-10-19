package wasidremin.gmccpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Equinox EV panel fitment: the panel is not a rectangle (top slopes down left-to-right,
 * right side cut away; vehicle reports safe insets 0/167/285/0 on a 2914x1134 window).
 * The 2026-10-18 full-window capture proved the bar's clock + temperature — the rightmost
 * items — were drawn at x 2700-2850, i.e. on missing glass.
 */
class TopBarFitTest {
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

    @Test fun settingsCeilingsCoverTheMeasuredCutout() {
        // safeInsetRight = 285 on the vehicle. Both the phone-side margin stops and the
        // surface-side dp slider must reach past it or the right edge stays under glass.
        assertTrue(DisplayPrefs.SAFE_PRESETS.max() >= 285)
        assertTrue(DisplayPrefs.RIGHT_DP_MAX >= 285)
    }
}
