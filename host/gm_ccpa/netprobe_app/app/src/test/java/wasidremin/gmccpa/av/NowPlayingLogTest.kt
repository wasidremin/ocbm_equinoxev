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

    /** 2026-11-02 17:13:13 and 2026-10-27 11:06:58: the same item, with and without its byline. */
    @Test fun aBylineVariantIsTheSameItem() {
        assertTrue(isBylineVariant("Landslide", "Landslide • The Chicks"))
        assertTrue(isBylineVariant("Landslide • The Chicks", "Landslide"))
        assertTrue(isBylineVariant("233. Election ", "233. Election  • The Rest Is Politics: US"))
        assertFalse(isBylineVariant("Landslide", "Landslide (Live)"))
        assertFalse(isBylineVariant("Landslide", "Dreams • Fleetwood Mac"))
        assertFalse(isBylineVariant("Landslide", "Landslide"))
        assertFalse(isBylineVariant(null, "Landslide • The Chicks"))
        assertFalse(isBylineVariant("", "• The Chicks"))
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

    /** 2026-11-08 12:53:38: the next song's length 0.54 s ahead of its title, which came with none. */
    @Test fun aLengthAheadOfItsTitleGoesToTheNewTitle() {
        var t = 0L
        val l = LengthAhead { t }
        // The old song keeps its own length while the new one is held.
        assertEquals(320587L, l.merge(trackChanged = false, titled = false, knownMs = 320587L, statedMs = 203573L))
        t += 540
        // The new title, stating no length, starts with the held one.
        assertEquals(203573L, l.merge(trackChanged = true, titled = true, knownMs = 0L, statedMs = null))
        // A length the new title states itself still wins.
        l.merge(false, false, 203573L, 222640L)
        t += 570
        assertEquals(222000L, l.merge(true, true, 0L, 222000L))
    }

    @Test fun aHeldLengthWithNoNewTitleWasTheCurrentSongs() {
        var t = 0L
        val l = LengthAhead { t }
        assertEquals(320587L, l.merge(false, false, 320587L, 203573L))
        t += LengthAhead.HOLD_MS + 1
        assertEquals(203573L, l.merge(false, false, 320587L, null))
        // Applied once; a title after that starts blank as before.
        assertEquals(0L, l.merge(true, true, 0L, null))
        // A late hold does not reach a title that comes after it.
        l.merge(false, false, 203573L, 100000L)
        t += LengthAhead.HOLD_MS + 1
        assertEquals(0L, l.merge(true, true, 0L, null))
    }

    @Test fun ordinaryLengthsMergeAsBefore() {
        val l = LengthAhead { 0L }
        // A re-stated length a millisecond apart, a first length, a titled change, a 0: no hold.
        assertEquals(320587L, l.merge(false, false, 320586L, 320587L))
        assertEquals(226667L, l.merge(false, false, 0L, 226667L))
        assertEquals(180000L, l.merge(false, true, 226667L, 180000L))
        assertEquals(180000L, l.merge(false, false, 180000L, 0L))
        assertEquals(0L, l.merge(true, true, 0L, null))
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

    /**
     * 2026-10-29 11:36:13, "the cure": title, art id 131, an 18307 B stand-in cover, art id 132 at
     * +536 ms, the real cover at +576 ms. Five cards went out, three without a picture. Held, only
     * the two with a picture reach the car.
     */
    @Test fun cardWaitsWhileItsPictureIsComing() {
        val standIn = byteArrayOf(1)
        val cover = byteArrayOf(2)
        val title = NowPlayingState.Snapshot(title = "the cure", artist = "a", durationMs = 297090L)
        val steps = listOf(
            title,
            title.copy(artworkId = 131),
            title.copy(artworkId = 131, artwork = standIn),
            title.copy(artworkId = 132),
            title.copy(artworkId = 132, artwork = cover),
        )
        var shownTitle: String? = "Housewife"
        var shownHasArt = true
        val published = mutableListOf<NowPlayingState.Snapshot>()
        for (s in steps) {
            if (holdForArt(shownTitle, shownHasArt, s)) continue
            published += s
            shownTitle = s.title
            shownHasArt = s.artwork != null
        }
        assertEquals(listOf(steps[2], steps[4]), published)
    }

    @Test fun trackWithNoPictureWaitsOnce() {
        val s = NowPlayingState.Snapshot(title = "Episode", artist = "a")
        assertTrue(holdForArt("Previous", true, s))
        // Once it has gone out without a picture, later updates to it go straight out.
        assertFalse(holdForArt("Episode", false, s.copy(durationMs = 1000L)))
        // Nothing to show, nothing to hold.
        assertFalse(holdForArt(null, false, NowPlayingState.Snapshot()))
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
