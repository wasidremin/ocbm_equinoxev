package wasidremin.gmccpa.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class TurnCardTest {
    private val clock = { at: java.time.Instant, zone: java.time.ZoneId ->
        java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(at)
    }

    @Test fun theCardCarriesThePhonesOwnDistanceTheCueAndTheArrival() {
        val s = RouteGuidance.Snapshot(
            state = RouteGuidance.ROUTE_SET,
            timeRemainingSec = 12 * 60L,
            distanceToManeuverText = "0.3", distanceToManeuverUnits = 1,
            destTimeZoneOffsetMin = 0,
            current = RouteGuidance.Maneuver(index = 2, type = 2, afterRoadName = "Main St"),
        )
        val c = TurnCard.content(s, 0L, clock)!!
        assertEquals("nav_turn_right", c.icon)
        assertEquals("0.3 mi", c.distance)
        assertEquals("Main St", c.road)
        assertEquals("00:12 · 12 min", c.arrival)
    }

    @Test fun noRouteNoCard() {
        assertNull(TurnCard.content(RouteGuidance.Snapshot(state = RouteGuidance.NO_ROUTE), 0L, clock))
        assertNull(TurnCard.content(RouteGuidance.Snapshot(state = RouteGuidance.ROUTE_SET), 0L, clock))
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
        assertEquals("1 h 05 min", TurnCard.duration(65 * 60L))
    }
}
