package wasidremin.gmccpa

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** The Now Playing tile is square; the art it shows is cropped to its centred square. */
class BarArtworkTest {
    @Test fun squareArtIsUsedWhole() {
        assertArrayEquals(intArrayOf(0, 0, 300, 300), centreSquare(300, 300))
    }

    @Test fun wideArtLosesItsSides() {
        assertArrayEquals(intArrayOf(80, 0, 400, 320), centreSquare(480, 320))
    }

    @Test fun tallArtLosesTopAndBottom() {
        assertArrayEquals(intArrayOf(0, 50, 200, 250), centreSquare(200, 300))
    }
}
