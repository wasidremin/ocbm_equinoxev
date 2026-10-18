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
    @Test fun zeroInsetKeepsHistoricalRightAlignment() {
        assertEquals(2914f - 168f * 0.35f, DisplayGeometry.clusterRightEdge(2914f, 168f, 0), 0.001f)
    }

    @Test fun measuredCutoutShiftsClusterOntoGlass() {
        val edge = DisplayGeometry.clusterRightEdge(2914f, 168f, 285)
        assertEquals(2914f - 168f * 0.35f - 285f, edge, 0.001f)
        // Rightmost glyph ends at the edge: 2570 < 2629, clear of the cutout.
        assertTrue(edge < 2629f)
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
