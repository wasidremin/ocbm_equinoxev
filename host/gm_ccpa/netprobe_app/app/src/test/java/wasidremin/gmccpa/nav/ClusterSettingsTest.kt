package wasidremin.gmccpa.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wasidremin.gmccpa.lyrics.BarLyrics
import wasidremin.gmccpa.pane.MediaPaneView

/** Settings ▸ Cluster as reworked 2026-11-14: the feed, which turn card, and what holds the panel. */
class ClusterSettingsTest {
    private val turn = ClusterContent.Turn.OURS
    private val apple = ClusterContent.Turn.APPLE
    private val off = ClusterContent.Turn.OFF

    @Test fun applesTurnCardTakesTheFeedsPictureWhileItIsChosen() {
        val map = ClusterContent.Background.MAP
        val app = ClusterContent.Background.APP
        assertEquals(ClusterContent.Choice.MAP, ClusterContent.choice(map, applesCard = false))
        assertEquals(ClusterContent.Choice.APP, ClusterContent.choice(app, applesCard = false))
        assertEquals(ClusterContent.Choice.CARD, ClusterContent.choice(map, applesCard = true))
        assertEquals(ClusterContent.Choice.CARD, ClusterContent.choice(app, applesCard = true))
    }

    @Test fun theTurnCardIsOffThisAppsOrApples() {
        assertEquals(off, ClusterContent.turn(applesCard = false, ownCard = false))
        assertEquals(turn, ClusterContent.turn(applesCard = false, ownCard = true))
        // Apple's wins: this app's card is hidden while Apple's has the feed, whatever its own switch says.
        assertEquals(apple, ClusterContent.turn(applesCard = true, ownCard = true))
        assertEquals(apple, ClusterContent.turn(applesCard = true, ownCard = false))
        assertEquals(3, ClusterContent.Turn.values().map { it.key }.toSet().size)
    }

    @Test fun thePanelIsRememberedForEachTurnCard() {
        val slot = ClusterLayout.Slot.values().associateBy { it.key }
        // Nothing stored: nothing docked, unless the turn card was docked before the panel took other cards.
        assertNull(ClusterLayout.dockedFor(turn, null, oldDock = false))
        assertEquals(ClusterLayout.Slot.TURN, ClusterLayout.dockedFor(turn, null, oldDock = true))
        assertNull(ClusterLayout.dockedFor(apple, null, oldDock = true))
        assertNull(ClusterLayout.dockedFor(off, null, oldDock = true))
        // Stored: that card, for any turn card.
        for (t in ClusterContent.Turn.values()) {
            assertEquals(ClusterLayout.Slot.MEDIA, ClusterLayout.dockedFor(t, ClusterLayout.Slot.MEDIA.key, oldDock = true))
            assertEquals(ClusterLayout.Slot.DRIVE, ClusterLayout.dockedFor(t, ClusterLayout.Slot.DRIVE.key, oldDock = false))
            assertNull(ClusterLayout.dockedFor(t, "none", oldDock = true))
            assertNull(ClusterLayout.dockedFor(t, "something else", oldDock = true))
        }
        // The turn card can hold the panel only when it is this app's own.
        assertEquals(ClusterLayout.Slot.TURN, ClusterLayout.dockedFor(turn, ClusterLayout.Slot.TURN.key, oldDock = false))
        assertNull(ClusterLayout.dockedFor(apple, ClusterLayout.Slot.TURN.key, oldDock = false))
        assertNull(ClusterLayout.dockedFor(off, ClusterLayout.Slot.TURN.key, oldDock = false))
        assertEquals(3, slot.size)
    }

    @Test fun theMediaPanesCoverIsLargeOnlyWhenASongHasNoLyricsToShow() {
        val s = BarLyrics.Status.values()
        // No cover: never. Lyrics found: the thumbnail, so they have the height.
        assertTrue(s.none { MediaPaneView.largeArt(hasArt = false, status = it, was = true) })
        assertFalse(MediaPaneView.largeArt(true, BarLyrics.Status.FOUND, was = true))
        // None, off, unreachable, nothing playing: the cover takes their place.
        for (st in listOf(BarLyrics.Status.NONE, BarLyrics.Status.OFF, BarLyrics.Status.UNREACHABLE, BarLyrics.Status.NO_SONG))
            assertTrue(st.name, MediaPaneView.largeArt(true, st, was = false))
        // Still looking: as it was, so the layout does not flip twice a song.
        assertTrue(MediaPaneView.largeArt(true, BarLyrics.Status.LOOKING, was = true))
        assertFalse(MediaPaneView.largeArt(true, BarLyrics.Status.LOOKING, was = false))
    }
}
