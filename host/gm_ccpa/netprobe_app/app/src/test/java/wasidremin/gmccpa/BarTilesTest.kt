package wasidremin.gmccpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The nine top-bar tiles: their order (the owner's, 2026-10-22) and that the whole level row is
 * on visible glass on the Equinox's sloped panel. Vehicle constants as in [TopBarFitTest].
 */
class BarTilesTest {
    private val w = 2914f
    private val h = 168f
    private val slopeM = 167f / 2629f
    private val margin = h * 0.05f

    @Test fun tilesAreInTheRequestedOrder() {
        assertEquals(
            // Owner's order (2026-10-23): our app first (CarPlay surfaces, then app pages),
            // then the car's own apps.
            listOf("CP_HOME", "CP_MAPS", "CP_SPOTIFY", "CP_PHONE", "APP_SETTINGS", "INSIGHTS",
                "AAOS_HOME", "CHARGING", "AAOS_SETTINGS"),
            BarTarget.values().map { it.name },
        )
    }

    @Test fun everyTileHasADescription() {
        assertTrue(BarTarget.values().all { it.description.isNotBlank() })
    }

    @Test fun theTwoFamiliesAreContiguousOursFirst() {
        // The bar draws one divider and badges a trailing block, so all OURS must precede all CAR.
        val groups = BarTarget.values().map { it.group }
        val firstCar = groups.indexOfFirst { it == BarGroup.CAR }
        assertTrue("there must be a car group", firstCar > 0)
        assertTrue("our-app tiles must all come before car-app tiles",
            groups.take(firstCar).all { it == BarGroup.OURS } &&
                groups.drop(firstCar).all { it == BarGroup.CAR })
    }

    @Test fun dividerFallsInsideTheGroupGapOnTheVehicle() {
        val n = BarTarget.values().size
        val breakAfter = BarTarget.values().indexOfFirst { it.group == BarGroup.CAR }
        val row = DisplayGeometry.topBarTiles(h, slopeM, n, h * 0.42f, margin,
            breakAfter = breakAfter, groupGapFrac = 0.6f)
        assertTrue("there is a real break", row.breakAfter == breakAfter && row.groupGap > 0f)
        val aRight = row.tileLeft(breakAfter - 1) + row.size   // last our-app tile's right edge
        val bLeft = row.tileLeft(breakAfter)                   // first car-app tile's left edge
        assertTrue("divider ${row.dividerX} must sit between $aRight and $bLeft",
            row.dividerX in aRight..bLeft)
        // The group gap is genuinely wider than an ordinary inter-tile gap.
        assertTrue(bLeft - aRight > row.pitch - row.size + 0.01f)
    }

    @Test fun clusterKeepsAWiderGapFromTheTiles() {
        // The sacred gap defaults to 0.8 x bar height now; the cluster never comes closer.
        val contentH = h * 0.24f
        val natural = 560f
        val layout = DisplayGeometry.topBarLayout(w, h, 285, slopeM, contentH, margin, 1000f, natural)
        if (layout.rightScale > 0f) {
            assertTrue("gap ${layout.gap} must be >= 0.8h", layout.gap >= h * 0.8f - 0.01f)
        }
    }

    @Test fun lastTileTopClearsTheSlopeOnTheVehicle() {
        val row = DisplayGeometry.topBarTiles(h, slopeM, 9, h * 0.42f, margin)
        // The steepest point under the row is its right edge.
        assertTrue("tile top ${row.top} must clear slope ${slopeM * row.end}",
            row.top >= slopeM * row.end + margin - 0.01f)
        // and the row stays inside the bar, off the hairline.
        assertTrue(row.top + row.size <= h - margin)
        assertTrue(row.size > 0f)
    }

    @Test fun flatPanelGetsFullSizeTiles() {
        val row = DisplayGeometry.topBarTiles(80f, 0f, 9, 80f * 0.42f, 80f * 0.05f)
        assertEquals(80f * 0.66f, row.size, 0.01f)
        assertEquals(row.size * 1.28f, row.pitch, 0.01f)
    }

    @Test fun steeperSlopeShrinksTheTiles() {
        val gentle = DisplayGeometry.topBarTiles(h, 0.03f, 9, 70f, margin)
        val steep = DisplayGeometry.topBarTiles(h, 0.10f, 9, 70f, margin)
        assertTrue(steep.size < gentle.size)
    }

    @Test fun absurdSlopeNeverGoesBelowTheFloor() {
        val row = DisplayGeometry.topBarTiles(h, 5f, 9, 70f, margin)
        assertEquals(h * 0.34f, row.size, 0.01f)
    }

    @Test fun crushedRightClusterShrinksTheRow() {
        val roomy = DisplayGeometry.topBarTiles(h, slopeM, 9, 70f, margin)
        // A cluster that only fits (>= 0.8 scale) when the row ends before x = 700.
        val squeezed = DisplayGeometry.topBarTiles(h, slopeM, 9, 70f, margin,
            rightClusterRoom = { end -> if (end < 700f) 1f else 0.5f })
        assertTrue(squeezed.size <= roomy.size)
        assertTrue("row end ${squeezed.end}", squeezed.end < 700f || squeezed.size <= h * 0.34f + 0.01f)
    }

    @Test fun clusterFitsBesideTheRowOnTheVehicle() {
        val contentH = h * 0.24f
        val natural = 640f
        val row = DisplayGeometry.topBarTiles(h, slopeM, 9, h * 0.42f, margin,
            rightClusterRoom = { end ->
                DisplayGeometry.topBarLayout(w, h, 285, slopeM, contentH, margin, end, natural).rightScale
            })
        val layout = DisplayGeometry.topBarLayout(w, h, 285, slopeM, contentH, margin, row.end, natural)
        assertTrue("cluster scale ${layout.rightScale}", layout.rightScale >= 0.8f || row.size <= h * 0.34f + 0.01f)
        assertTrue(layout.rightStart >= row.end)
    }
}
