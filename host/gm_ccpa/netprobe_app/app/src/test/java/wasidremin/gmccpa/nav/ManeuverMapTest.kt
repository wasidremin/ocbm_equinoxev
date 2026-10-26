package wasidremin.gmccpa.nav

import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Maneuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

/**
 * The parts of the Car App side that decide what the cluster shows. On the VCU cluster the TYPE is
 * the whole picture (GM draws its own glyph for it), so the table is pinned here entry by entry
 * where a mistake would draw the wrong arrow.
 */
class ManeuverMapTest {
    @Test fun basicTurns() {
        assertEquals(Maneuver.TYPE_TURN_NORMAL_LEFT, ManeuverMap.type(1, 0))
        assertEquals(Maneuver.TYPE_TURN_NORMAL_RIGHT, ManeuverMap.type(2, 0))
        assertEquals(Maneuver.TYPE_STRAIGHT, ManeuverMap.type(3, 0))
        assertEquals(Maneuver.TYPE_TURN_SHARP_LEFT, ManeuverMap.type(47, 0))
        assertEquals(Maneuver.TYPE_TURN_SHARP_RIGHT, ManeuverMap.type(48, 0))
        assertEquals(Maneuver.TYPE_TURN_SLIGHT_LEFT, ManeuverMap.type(49, 0))
        assertEquals(Maneuver.TYPE_TURN_SLIGHT_RIGHT, ManeuverMap.type(50, 0))
        assertEquals(Maneuver.TYPE_KEEP_LEFT, ManeuverMap.type(13, 0))
        assertEquals(Maneuver.TYPE_KEEP_RIGHT, ManeuverMap.type(14, 0))
    }

    @Test fun drivingSideFlipsUTurnsRoundaboutsAndRamps() {
        assertEquals(Maneuver.TYPE_U_TURN_LEFT, ManeuverMap.type(4, 0))
        assertEquals(Maneuver.TYPE_U_TURN_RIGHT, ManeuverMap.type(4, 1))
        assertEquals(Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW, ManeuverMap.type(30, 0))
        assertEquals(Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW, ManeuverMap.type(30, 1))
        assertEquals(Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT, ManeuverMap.type(8, 0))
        assertEquals(Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT, ManeuverMap.type(8, 1))
        // Unknown side reads as right-hand traffic.
        assertEquals(Maneuver.TYPE_U_TURN_LEFT, ManeuverMap.type(4, null))
    }

    @Test fun arrivalsAndDepart() {
        assertEquals(Maneuver.TYPE_DEPART, ManeuverMap.type(11, 0))
        for (t in intArrayOf(10, 12, 27)) assertEquals(Maneuver.TYPE_DESTINATION, ManeuverMap.type(t, 0))
        assertEquals(Maneuver.TYPE_DESTINATION_LEFT, ManeuverMap.type(24, 0))
        assertEquals(Maneuver.TYPE_DESTINATION_RIGHT, ManeuverMap.type(25, 0))
    }

    @Test fun roundaboutExitNumberComesFromTheType() {
        assertEquals(1, ManeuverMap.roundaboutExit(28))
        assertEquals(3, ManeuverMap.roundaboutExit(30))
        assertEquals(19, ManeuverMap.roundaboutExit(46))
        assertNull(ManeuverMap.roundaboutExit(6))
        assertNull(ManeuverMap.roundaboutExit(47))
        assertNull(ManeuverMap.roundaboutExit(null))
    }

    @Test fun absentOrFutureTypeIsUnknown() {
        assertEquals(Maneuver.TYPE_UNKNOWN, ManeuverMap.type(null, 0))
        assertEquals(Maneuver.TYPE_UNKNOWN, ManeuverMap.type(54, 0))
        assertEquals(Maneuver.TYPE_UNKNOWN, ManeuverMap.type(-1, 0))
    }

    @Test fun everyAppleTypeMapsToSomething() {
        for (t in 0..53) assertNotEquals("type $t unmapped", Maneuver.TYPE_UNKNOWN, ManeuverMap.type(t, 0))
    }

    // ---- distance -----------------------------------------------------------------------------

    private fun d(text: String?, units: Int?, meters: Long? = null, locale: Locale = Locale.US): Distance? =
        NavDistance.of(text, units, meters, locale)

    @Test fun phoneDisplayStringWinsInItsOwnUnits() {
        d("0.3", 1)!!.let { assertEquals(0.3, it.displayDistance, 1e-9); assertEquals(Distance.UNIT_MILES_P1, it.displayUnit) }
        d("12", 1)!!.let { assertEquals(12.0, it.displayDistance, 1e-9); assertEquals(Distance.UNIT_MILES, it.displayUnit) }
        d("500", 4)!!.let { assertEquals(500.0, it.displayDistance, 1e-9); assertEquals(Distance.UNIT_FEET, it.displayUnit) }
        d("1,2", 0)!!.let { assertEquals(1.2, it.displayDistance, 1e-9); assertEquals(Distance.UNIT_KILOMETERS_P1, it.displayUnit) }
        d("800", 2)!!.let { assertEquals(800.0, it.displayDistance, 1e-9); assertEquals(Distance.UNIT_METERS, it.displayUnit) }
        d("300", 3)!!.let { assertEquals(Distance.UNIT_YARDS, it.displayUnit) }
        // Whole units: a separator groups thousands.
        assertEquals(1000.0, d("1,000", 4)!!.displayDistance, 1e-9)
    }

    @Test fun parseDecimalSeparators() {
        assertEquals(0.3 to true, NavDistance.parseDecimal("0.3"))
        assertEquals(0.3 to true, NavDistance.parseDecimal("0,3"))
        assertEquals(1234.5 to true, NavDistance.parseDecimal("1,234.5"))
        assertEquals(1000.0 to false, NavDistance.parseDecimal("1,000"))
        assertEquals(12.0 to false, NavDistance.parseDecimal("12"))
        assertNull(NavDistance.parseDecimal("--"))
    }

    @Test fun metersFallBackThroughTheLocale() {
        // No display pair: carlink's thresholds. US -> feet under ~1000 ft, else miles.
        d(null, null, 100L, Locale.US)!!.let { assertEquals(Distance.UNIT_FEET, it.displayUnit); assertEquals(350.0, it.displayDistance, 1e-9) }
        d(null, null, 3218L, Locale.US)!!.let { assertEquals(Distance.UNIT_MILES_P1, it.displayUnit) }
        d(null, null, 400L, Locale.GERMANY)!!.let { assertEquals(Distance.UNIT_METERS, it.displayUnit); assertEquals(400.0, it.displayDistance, 1e-9) }
        // The phone's unit enum outranks the locale even when its string is missing.
        d(null, 0, 2500L, Locale.US)!!.let { assertEquals(Distance.UNIT_KILOMETERS_P1, it.displayUnit) }
        assertNull(d(null, null, null))
        assertNull(d("abc", 1, null))
    }

    // ---- ETA ----------------------------------------------------------------------------------

    @Test fun etaIsNowPlusRemainingInTheDestinationZone() {
        val now = 1_800_000_000_000L
        val s = RouteGuidance.Snapshot(timeRemainingSec = 600, destTimeZoneOffsetMin = -300)
        val eta = TripFactory.eta(s, now)
        assertEquals(now / 1000 + 600, eta.toEpochSecond())
        assertEquals(ZoneOffset.ofHours(-5), eta.offset)
        // Unknown remaining time: arrival "now", never in the past.
        assertEquals(now / 1000, TripFactory.eta(RouteGuidance.Snapshot(timeRemainingSec = -4), now).toEpochSecond())
    }
}
