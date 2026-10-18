package wasidremin.gmccpa.av

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The 2026-10-17 midday audit: records arrived with artworkIds but no titles, and the log gave
 * no evidence either way. These helpers drive the new diagnostics; both are pure.
 */
class NowPlayingLogTest {
    @Test fun titlelessFiresOncePerArtworkId() {
        // First sighting of an id reports it.
        assertEquals(129, titlelessRecordEvent(null, 129, -2))
        // Same id again stays quiet (no per-tick spam).
        assertNull(titlelessRecordEvent(null, 129, 129))
        // A new id reports again.
        assertEquals(130, titlelessRecordEvent(null, 130, 129))
    }

    @Test fun titledRecordsNeverReport() {
        assertNull(titlelessRecordEvent("Drop Dead", 129, -2))
        assertNull(titlelessRecordEvent("", null, -2))
        assertNull(titlelessRecordEvent(null, null, -2))
    }

    @Test fun trackSummaryNamesWhatChanged() {
        val line = trackSummaryLine("Drop Dead", true, 1, 129)
        assertEquals("np: track \"Drop Dead\" artist=yes status=1 artworkId=129", line)
    }

    @Test fun trackSummaryToleratesMissingFields() {
        val line = trackSummaryLine(null, false, null, null)
        assertEquals("np: track \"?\" artist=no status=? artworkId=?", line)
    }
}
