package wasidremin.gmccpa.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wasidremin.gmccpa.lyrics.BarLyrics.Status
import wasidremin.gmccpa.upload.DataMeter.Link

/** What the media pane says when a song shows no lyrics. */
class LyricsNoteTest {
    @Test fun aSongNotInThePackageAndNotAskedAboutDoesNotClaimItHasNoLyrics() {
        // 2026-11-27: it read "No timed lyrics for this song", and looked like the package failing.
        assertEquals("Not in the lyrics package, and the car has no connection. Looked up the next time it is on Wi-Fi.",
            lyricsNote(Status.WAITING, LyricsOnline.ALWAYS, Link.NONE))
        assertEquals("Not in the lyrics package. Looked up the next time the car is on Wi-Fi.",
            lyricsNote(Status.WAITING, LyricsOnline.WIFI, Link.CAR))
        assertTrue(lyricsNote(Status.WAITING, LyricsOnline.NEVER, Link.WIFI).contains("package is refreshed"))
    }

    @Test fun aSongWithNoArtistSaysSo() {
        assertTrue(lyricsNote(Status.NO_ARTIST, LyricsOnline.ALWAYS, Link.WIFI).contains("did not name the artist"))
    }

    @Test fun lrclibUnreachableSaysItIsAskedAgain() {
        assertTrue(lyricsNote(Status.UNREACHABLE, LyricsOnline.ALWAYS, Link.WIFI).contains("Asked again"))
        assertEquals("", lyricsNote(Status.FOUND, LyricsOnline.ALWAYS, Link.WIFI))
    }
}
