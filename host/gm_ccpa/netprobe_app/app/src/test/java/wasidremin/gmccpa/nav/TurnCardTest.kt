package wasidremin.gmccpa.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class TurnCardTest {
    private fun far(m: Long, next: RouteGuidance.Maneuver? = null, lanes: List<RouteGuidance.Lane>? = null) = RouteGuidance.Snapshot(
        state = RouteGuidance.ROUTE_SET,
        distanceToManeuverText = "0.3", distanceToManeuverUnits = 1, distanceToManeuverM = m,
        current = RouteGuidance.Maneuver(index = 2, type = 2, afterRoadName = "Main St"),
        next = next, lanes = lanes,
    )

    @Test fun farOffTheCardIsThePhonesDistanceAndTheRoad() {
        val c = TurnCard.content(far(3000))!!
        assertEquals("nav_turn_right", c.icon)
        assertEquals("0.3 mi", c.distance)
        assertEquals("Main St", c.road)
        assertEquals(false, c.near)
        assertNull(c.approach)
        assertNull(c.exit)
    }

    @Test fun insideHalfAMileTheCardGrowsAndCountsDown() {
        val c = TurnCard.content(far(200))!!
        assertEquals(true, c.near)
        assertEquals(0.75f, c.approach!!, 0.001f)
        // No metres from the phone: its display figure decides (0.3 mi = 483 m).
        val text = TurnCard.content(far(3000).copy(distanceToManeuverM = null))!!
        assertEquals(true, text.near)
        assertEquals(483L, TurnCard.meters("0.3", 1))
        assertNull(TurnCard.meters("0.3", null))
    }

    @Test fun theTurnAfterShowsOnlyWhenNearAndClose() {
        val close = RouteGuidance.Maneuver(index = 3, type = 1, afterRoadName = "Oak Ave", distanceText = "500", distanceUnits = 4)
        val c = TurnCard.content(far(200, close))!!
        assertEquals("nav_turn_left", c.thenIcon)
        assertEquals("Oak Ave", c.thenRoad)
        assertNull(TurnCard.content(far(3000, close))!!.thenIcon)
        assertNull(TurnCard.content(far(200, close.copy(distanceText = "2", distanceUnits = 1)))!!.thenIcon)
        assertNull(TurnCard.content(far(200, close.copy(distanceText = null)))!!.thenIcon)
    }

    @Test fun theExitNumberIsASignNotARoad() {
        val s = far(3000).let { it.copy(current = it.current!!.copy(exitInfo = "Exit 273", afterRoadName = "273")) }
        val c = TurnCard.content(s)!!
        assertEquals("273", c.exit)
        assertNull(c.road)
        assertEquals("12B", TurnCard.exitNumber(" 12B "))
        assertNull(TurnCard.exitNumber(" "))
    }

    @Test fun lanesShowOnlyWhenAskedForAndNear() {
        val lanes = listOf(RouteGuidance.Lane(0, 0, listOf(-90)), RouteGuidance.Lane(1, 2, listOf(0, 90), 90))
        assertEquals(lanes, TurnCard.content(far(200, lanes = lanes), lanes = true)!!.lanes)
        assertNull(TurnCard.content(far(200, lanes = lanes))!!.lanes)
        assertNull(TurnCard.content(far(3000, lanes = lanes), lanes = true)!!.lanes)
        assertNull(TurnCard.content(far(200, lanes = lanes.take(1)), lanes = true)!!.lanes)
    }

    @Test fun noRouteNoCard() {
        assertNull(TurnCard.content(RouteGuidance.Snapshot(state = RouteGuidance.NO_ROUTE)))
        assertNull(TurnCard.content(RouteGuidance.Snapshot(state = RouteGuidance.ROUTE_SET)))
    }

    @Test fun distancesFallBackToMetresInTheLocalesUnits() {
        assertEquals("500 ft", TurnCard.distance("500", 4, null))
        assertEquals("150 ft", TurnCard.distance(null, null, 50, Locale.US))
        assertEquals("1.6 mi", TurnCard.distance(null, null, 2600, Locale.US))
        assertEquals("450 m", TurnCard.distance(null, null, 455, Locale.GERMANY))
        assertEquals("2.6 km", TurnCard.distance(null, 0, 2600, Locale.US))
        assertNull(TurnCard.distance(null, 1, null))
    }

    @Test fun iconsFollowTheTurnAndTheSideOfTheRoad() {
        assertEquals("nav_turn_left", TurnCard.icon(1, 0))
        assertEquals("nav_uturn_left", TurnCard.icon(4, 0))
        assertEquals("nav_uturn_right", TurnCard.icon(4, 1))
        assertEquals("nav_roundabout_ccw", TurnCard.icon(30, 0))
        assertEquals("nav_roundabout_cw", TurnCard.icon(30, 1))
        assertEquals("nav_destination", TurnCard.icon(12, 0))
        assertEquals("nav_straight", TurnCard.icon(99, 0))
    }

    @Test fun aCardAndWhatHangsUnderItStayOnTheDisplay() {
        val turn = ClusterLayout.Slot.TURN
        val b = ClusterLayout.rect(2000, 810, turn, turn.default)
        assertEquals(178.2f, b.height, 0.01f)
        assertEquals(178.2f * 2.9f, b.width, 0.01f)
        // Left of GM's directions area, which starts 28% across.
        assertEquals(true, b.left + b.width <= 2000 * 0.28f)
        assertEquals(40f, b.left, 0.01f)
        // Above the Equinox's gauges, which take the bottom third.
        assertEquals(true, b.top + b.height * turn.stack <= 810 * 0.66f)
        // Dragged off the corner: pushed back in, stack and all.
        val corner = ClusterLayout.rect(2000, 810, turn, ClusterLayout.Place(100, 100))
        assertEquals(2000f, corner.left + corner.width, 0.01f)
        assertEquals(810f, corner.top + corner.height * turn.stack, 0.01f)
        // Too big to fit: shrunk, never clipped.
        for (slot in ClusterLayout.Slot.values()) {
            val big = ClusterLayout.rect(2000, 810, slot, ClusterLayout.Place(50, 50, 900))
            assertEquals(true, big.left >= 0f && big.left + big.width <= 2000.01f)
            assertEquals(true, big.top >= 0f && big.top + big.height * slot.stack <= 810.01f)
            assertEquals(slot.aspect, big.width / big.height, 0.01f)
        }
        assertEquals(ClusterLayout.Place(0, 100, ClusterLayout.MIN_SCALE), ClusterLayout.Place(-5, 140, 10).clamped())
    }

    @Test fun theDockedCardKeepsItsCornerOnTheDisplay() {
        val b = ClusterLayout.dockRect(2000, 810, ClusterLayout.DOCK_DEFAULT)
        assertEquals(1400f, b.left, 0.01f)
        assertEquals(437.4f, b.top, 0.01f)
        assertEquals(178.2f, b.height, 0.01f)
        val off = ClusterLayout.dockRect(2000, 810, ClusterLayout.Place(100, 0))
        assertEquals(1900f, off.left, 0.01f)
        assertEquals(81f, off.top, 0.01f)
    }

    @Test fun nowPlayingOpensOnANewSongAndFoldsDuringARoute() {
        assertEquals(MediaCard.Mode.HIDDEN, MediaCard.mode(false, false, 0))
        assertEquals(MediaCard.Mode.OPEN, MediaCard.mode(true, false, 60_000))
        assertEquals(MediaCard.Mode.OPEN, MediaCard.mode(true, true, MediaCard.OPEN_MS - 1))
        assertEquals(MediaCard.Mode.CHIP, MediaCard.mode(true, true, MediaCard.OPEN_MS))
        assertEquals(0.25f, MediaCard.progress(30_000, 120_000)!!, 0.001f)
        assertNull(MediaCard.progress(30_000, 0))
        assertEquals("1:05", MediaCard.callTime(65_000))
        assertEquals("1:01:05", MediaCard.callTime(3_665_000))
    }
}
