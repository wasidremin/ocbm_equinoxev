package wasidremin.gmccpa.av

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The 2026-10-18 drive: the phone splits a track change across frames — title first with no
 * artworkId, the id in a title-less follow-up, the JPEG after that. Keeping the previous id
 * across the title frame pasted the old cover onto the new title; clearing to unknown is a
 * brief blank instead of a wrong picture.
 */
class ArtworkKeeperTest {
    private val logs = mutableListOf<String>()
    private val fires = mutableListOf<() -> Boolean>()
    private fun keeper() = ArtworkKeeper(
        log = { logs.add(it) },
        afterMs = { _, fire -> fires.add(fire) },
    )

    private val jpeg1 = byteArrayOf(1, 2, 3)
    private val jpeg2 = byteArrayOf(4, 5, 6)

    private fun withArt(): ArtworkKeeper {
        val k = keeper()
        k.onRecord(false, 131)
        assertEquals("accepted", k.onTransfer(131, jpeg1, true))
        return k
    }

    @Test fun titleWithoutIdClearsPreviousArt() {
        val k = withArt()
        k.onRecord(true, null)
        assertEquals(-1, k.artworkId)
        assertNull(k.artwork)
    }

    @Test fun titleWithSameIdKeepsArt() {
        val k = withArt()
        k.onRecord(true, 131)
        assertEquals(131, k.artworkId)
        assertArrayEquals(jpeg1, k.artwork)
    }

    @Test fun idFollowUpThenTransferRestores() {
        val k = withArt()
        k.onRecord(true, null)
        k.onRecord(false, 132)
        assertEquals(132, k.artworkId)
        assertNull(k.artwork)
        assertEquals("accepted", k.onTransfer(132, jpeg2, true))
        assertArrayEquals(jpeg2, k.artwork)
    }

    @Test fun adoptFirstTransferWhenIdUnknown() {
        val k = withArt()
        k.onRecord(true, null)
        assertEquals("accepted", k.onTransfer(140, jpeg2, true))
        assertEquals(140, k.artworkId)
        assertArrayEquals(jpeg2, k.artwork)
    }

    @Test fun elapsedTicksKeepCurrentId() {
        val k = withArt()
        k.onRecord(false, null)
        assertEquals(131, k.artworkId)
        assertArrayEquals(jpeg1, k.artwork)
    }
}
