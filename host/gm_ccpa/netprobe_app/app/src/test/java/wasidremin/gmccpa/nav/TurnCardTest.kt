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

    @Test fun theCardAreaFollowsTheDirectionsAreaUntilSet() {
        val size = wasidremin.gmccpa.ocbm.PixelSize(2000, 810)
        val gm = DriverDisplay.GM_LAYOUT
        assertEquals(DriverDisplay.safeArea(size, gm), DriverDisplay.cardRect(size, null, gm))
        assertEquals(wasidremin.gmccpa.ocbm.PixelRect(0, 0, 2000, 810), DriverDisplay.cardRect(size, null, DriverDisplay.Margins()))
        // Left side: 40..560 across, 48..534 down, left of the directions area and above the gauges.
        val left = DriverDisplay.cardRect(size, DriverDisplay.CARD_LEFT, gm)
        assertEquals(wasidremin.gmccpa.ocbm.PixelRect(40, 48, 520, 487), left)
        assertEquals(true, left.right <= DriverDisplay.safeArea(size, gm)!!.x)
    }

    @Test fun oppositeEdgesAlwaysLeaveTenPercent() {
        val c = DriverDisplay.cardAreaClamped(DriverDisplay.Margins(left = 95, top = 50, right = 50, bottom = 60))
        assertEquals(DriverDisplay.Margins(left = 90, top = 50, right = 0, bottom = 40), c)
    }

    @Test fun theCardShrinksToItsArea() {
        // Roomy: a quarter of the window high, 2.9 times as wide.
        val big = TurnCard.box(wasidremin.gmccpa.ocbm.PixelRect(0, 0, 2000, 810), 810)!!
        assertEquals(202.5f, big.height, 0.01f)
        assertEquals(202.5f * 2.9f, big.width, 0.01f)
        // The left strip: narrower than 2.9 h, so the width gives, the height does not.
        val strip = wasidremin.gmccpa.ocbm.PixelRect(40, 48, 520, 487)
        val b = TurnCard.box(strip, 810)!!
        assertEquals(202.5f, b.height, 0.01f)
        assertEquals(true, b.left + b.width + b.pad <= strip.right + 0.01f)
        // Short or narrow areas shrink the card; it never leaves them.
        for (a in listOf(wasidremin.gmccpa.ocbm.PixelRect(0, 0, 2000, 120), wasidremin.gmccpa.ocbm.PixelRect(10, 10, 300, 600))) {
            val s = TurnCard.box(a, 810)!!
            assertEquals(true, s.top + s.height + s.pad <= a.y + a.height + 0.01f)
            assertEquals(true, s.left + s.width + s.pad <= a.right + 0.01f)
            assertEquals(true, s.width >= s.height * 2.2f - 0.01f)
        }
        assertNull(TurnCard.box(wasidremin.gmccpa.ocbm.PixelRect(0, 0, 10, 10), 810))
    }
}
