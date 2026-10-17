package wasidremin.gmccpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class DisplayGeometryTest {
    @Test fun zeroInsetsPreserveDefault() {
        assertEquals(DisplayGeometry.Box(0, 0, 1024, 1100),
            DisplayGeometry.content(1024, 1100, 1f, DisplayPrefs.Controls()))
    }

    @Test fun asymmetricInsetsAreExactAndDensityAware() {
        val c = DisplayPrefs.Controls(top = 10, bottom = 20, left = 30, right = 40)
        assertEquals(DisplayGeometry.Box(30, 10, 954, 1070), DisplayGeometry.content(1024, 1100, 1f, c))
        assertEquals(DisplayGeometry.Box(60, 20, 884, 1040), DisplayGeometry.content(1024, 1100, 2f, c))
    }

    @Test fun fitCentersWithoutCropping() {
        val area = DisplayGeometry.Box(30, 10, 954, 1070)
        assertEquals(DisplayGeometry.Box(30, 276, 954, 537), DisplayGeometry.fit(area, 1920, 1080))
        assertEquals(DisplayGeometry.Box(0, 0, 1024, 1100),
            DisplayGeometry.fit(DisplayGeometry.Box(0, 0, 1024, 1100), 1024, 1100))
    }

    @Test fun newUiScaleCannotChangeSurfaceGeometry() {
        val base = DisplayPrefs.Controls(top = 10, bottom = 20, left = 30, right = 40)
        val expected = DisplayGeometry.content(1024, 1100, 1f, base)
        for (scale in 50..150) {
            assertEquals(expected, DisplayGeometry.content(1024, 1100, 1f, base.copy(uiPercent = scale)))
        }
    }

    @Test fun extremeInsetsNeverProduceNegativeDimensions() {
        val area = DisplayGeometry.content(50, 50, 4f, DisplayPrefs.Controls(160, 160, 160, 160))
        assertTrue(area.width > 0 && area.height > 0)
        assertTrue(area.x + area.width <= 50 && area.y + area.height <= 50)
    }

    @Test fun matchingLatchIsCenteredOneToOne() {
        val fit = DisplayGeometry.fitLatched(2914, 1134, 2914, 1134)
        assertEquals(1f, fit.scale)
        assertEquals(0, fit.x)
        assertEquals(0, fit.y)
        assertTrue(fit.matched)
    }

    @Test fun onePixelEvenRoundingIsNotAMismatch() {
        val fit = DisplayGeometry.fitLatched(2629, 967, 2629, 966)
        assertEquals(1f, fit.scale)
        assertTrue(fit.matched)
    }

    @Test fun largerWindowCentersWithoutScaling() {
        val fit = DisplayGeometry.fitLatched(2914, 1134, 2754, 966)
        assertEquals(1f, fit.scale)
        assertEquals(80, fit.x)
        assertEquals(84, fit.y)
    }

    @Test fun smallerWindowDownscalesToFitInsteadOfOverflowing() {
        val fit = DisplayGeometry.fitLatched(2629, 967, 2914, 1134)
        assertTrue(!fit.matched)
        assertTrue(fit.scale < 1f && fit.scale > 0.8f)
        // The scaled block must fit inside the window.
        assertTrue((2914 * fit.scale).roundToInt() <= 2629)
        assertTrue((1134 * fit.scale).roundToInt() <= 967)
    }
}
