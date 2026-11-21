package wasidremin.gmccpa.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Route A's own pieces here: the screen's fit, the record test before decoding, and the FairPlay check. */
class RouteATest {
    @Test fun theVideoKeepsItsAspectInsideTheScreenAndFillsItUntilTheAspectIsKnown() {
        assertEquals(2914 to 1134, VideoOverlayView.fit(2914, 1134, 0f))
        // A 16:9 video on the Equinox's wide panel: full height, centred, black either side.
        assertEquals(2016 to 1134, VideoOverlayView.fit(2914, 1134, 16f / 9f))
        // A portrait video on a narrow box: full width.
        assertEquals(600 to 1066, VideoOverlayView.fit(600, 2000, 9f / 16f))
    }

    @Test fun onlyTheAdaptersDataStreamRecordsGoToThePlayQueue() {
        assertTrue(RouteA.isDataStreamRecord("""{"kind":"dataStream","stream":3,"client":null,"message":{}}""".toByteArray()))
        assertFalse(RouteA.isDataStreamRecord("""{"kind":"nowPlaying","title":"x"}""".toByteArray()))
        assertFalse(RouteA.isDataStreamRecord("""{"kind":"data""".toByteArray()))
    }

    @Test fun aPlaylistWithFairPlayKeysIsDeclinedAndAClearOneIsNot() {
        assertTrue(ExoVideoEngine.fairPlay(listOf("#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://key\",KEYFORMAT=\"com.apple.streamingkeydelivery\"")))
        assertTrue(ExoVideoEngine.fairPlay(listOf("#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI=\"skd://k\"")))
        assertFalse(ExoVideoEngine.fairPlay(listOf("#EXT-X-KEY:METHOD=AES-128,URI=\"https://k\"", "#EXTINF:6.0,")))
    }
}
