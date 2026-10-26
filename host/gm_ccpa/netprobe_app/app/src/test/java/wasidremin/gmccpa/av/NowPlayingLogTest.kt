package wasidremin.gmccpa.av

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /**
     * 2026-10-20 drive: at 21:37:07 the card's `dur=2438400` became `dur=0` while the same track was
     * still playing (iOS re-stated the duration as 0), and at 22:21:37 a track-change frame with no
     * PlaybackAttributes published `state=3 pos=0 rate=1.0`, i.e. "just started" and remaining = full
     * length, until the next tick snapped it back.
     */
    @Test fun zeroDurationNeverErasesAKnownOne() {
        assertEquals(2438400L, mergeDuration(2438400L, 0L))
        assertEquals(2438400L, mergeDuration(2438400L, null))
        assertEquals(0L, mergeDuration(0L, 0L))       // a new track with no duration yet stays unknown
        assertEquals(226667L, mergeDuration(0L, 226667L))
        assertEquals(163855L, mergeDuration(163855L, 163855L))
    }

    @Test fun unknownPositionIsNotZero() {
        assertEquals(POSITION_UNKNOWN, publishedPosition(elapsedKnown = false, elapsedMs = 0L,
            focusPaused = false, frozenPosition = 0L))
        assertEquals(134475L, publishedPosition(elapsedKnown = true, elapsedMs = 134475L,
            focusPaused = false, frozenPosition = 0L))
        // A focus pause still pins the position taken from the last real push.
        assertEquals(63684L, publishedPosition(elapsedKnown = false, elapsedMs = 0L,
            focusPaused = true, frozenPosition = 63684L))
    }

    @Test fun snapshotIgnoresElapsedKnownForMetadataEquality() {
        val a = NowPlayingState.Snapshot(title = "T", durationMs = 1000L)
        val b = a.copy(elapsedKnown = false)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test fun shouldFireTickOnlyWhenElapsedMoves() {
        org.junit.Assert.assertTrue(shouldFireTick(1000L, 1500L))
        org.junit.Assert.assertFalse(shouldFireTick(1500L, 1500L))
        org.junit.Assert.assertFalse(shouldFireTick(0L, 0L))
    }

    // ---- music-sync heartbeat (30 s `np: sync` line) ---------------------------------------

    @Test fun emptyTallyIsDistinguishableFromATickOnlyWindow() {
        val t = SyncTally()
        assertTrue(t.isEmpty())
        // A tick-only beat is NOT silent: the seam carried records, they just changed nothing.
        t.records = 60
        t.ticks = 60
        assertFalse(t.isEmpty())
        // Artwork alone is traffic too.
        val art = SyncTally()
        art.artwork = 1
        assertFalse(art.isEmpty())
        // …and so is navigation alone: a seam carrying only route records is still up.
        val nav = SyncTally()
        nav.nav = 60
        assertFalse(nav.isEmpty())
        assertTrue(nav.line(30L).contains("records=0"))
        assertTrue(nav.line(30L).contains("nav=60"))
        // …and a kind we ignore.
        val other = SyncTally()
        other.ignored = 12
        other.lastIgnoredKind = "artworkReady"
        assertFalse(other.isEmpty())
    }

    @Test fun beatLineCarriesEveryCount() {
        val t = SyncTally().apply {
            records = 12; titles = 1; metadata = 1; ticks = 11
            artwork = 1; artAccepted = 1; nav = 30; ignored = 3; lastIgnoredKind = "callState"
        }
        assertEquals(
            "np: sync 30s records=12 (titleChanges=1 metadata=1 ticks=11) " +
                "artwork=1 accepted=1 nav=30 ignoredKinds=3 lastIgnored=callState",
            t.line(30L),
        )
        // clear() must reset the kind name too, or the next beat names a stale kind.
        t.clear()
        assertTrue(t.isEmpty())
        assertNull(t.lastIgnoredKind)
    }

    @Test fun sessionSummarySeparatesNeverFromLater() {
        assertEquals(
            "np: sync session records=0 artwork=0 firstRecordAfter=never",
            syncSummaryLine(0L, 0L, null),
        )
        assertEquals(
            "np: sync session records=412 artwork=2 firstRecordAfter=7s",
            syncSummaryLine(412L, 2L, 7L),
        )
    }
}
