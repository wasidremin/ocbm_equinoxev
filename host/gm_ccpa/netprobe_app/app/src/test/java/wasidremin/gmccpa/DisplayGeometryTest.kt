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

    @Test fun settingsFrameMatchesVideoLatchOneToOne() {
        // Window equals the latch: settings fill the window minus the presentation insets.
        val box = DisplayGeometry.settingsFrame(2400, 960, 2400, 960,
            DisplayPrefs.Controls(), 3f)
        assertEquals(DisplayGeometry.Box(0, 0, 2400, 960), box)
    }

    @Test fun settingsFrameAppliesPresentationInsetsScaled() {
        val c = DisplayPrefs.Controls(top = 10, bottom = 20, left = 30, right = 40)
        val box = DisplayGeometry.settingsFrame(1024, 1100, 1024, 1100, c, 1f)
        assertEquals(DisplayGeometry.Box(30, 10, 954, 1070), box)
    }

    @Test fun settingsFrameCentersSmallerLatch() {
        // Latch smaller than the window: the frame centers like the video block.
        val box = DisplayGeometry.settingsFrame(2914, 1134, 2754, 966,
            DisplayPrefs.Controls(), 1f)
        assertEquals(80, box.x)
        assertEquals(84, box.y)
        assertEquals(2754, box.width)
        assertEquals(966, box.height)
    }

    @Test fun settingsFrameDownscalesWithSmallerWindow() {
        // Window smaller than the latch: the frame shrinks instead of parking off-screen.
        val box = DisplayGeometry.settingsFrame(2629, 967, 2914, 1134,
            DisplayPrefs.Controls(), 1f)
        assertTrue(box.x >= 0 && box.y >= 0)
        assertTrue(box.x + box.width <= 2629)
        assertTrue(box.y + box.height <= 967)
        assertTrue(box.width > 0 && box.height > 0)
    }

    @Test fun settingsFrameNeverNegative() {
        val box = DisplayGeometry.settingsFrame(50, 50, 2400, 960,
            DisplayPrefs.Controls(160, 160, 160, 160), 4f)
        assertTrue(box.width > 0 && box.height > 0)
        assertTrue(box.x + box.width <= 50 && box.y + box.height <= 50)
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
