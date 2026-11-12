package wasidremin.gmccpa.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [DriveOutlook] on made-up records. Coordinates are an invented grid near 0°, 0°. */
class DriveOutlookTest {
    private val day = TripStats.DAY_MS
    private val home = 0.00 to 0.00
    private val work = 0.20 to 0.00
    private val warmKm = TripRecorder.WARM_M / 1000

    /**
     * A drive of [km] at a steady [mps] whose road after the warm-up cost [k] times the model's rate,
     * with a warm-up of [warmupWh] on top. [speedNow] above zero is a drive caught still moving.
     */
    private fun drive(t0: Long, km: Double, mps: Double, k: Double, tempC: Double? = 18.0, warmupWh: Double = DriveOutlook.WARMUP_WH,
                      from: Pair<Double, Double>? = null, to: Pair<Double, Double>? = null, open: Boolean = false,
                      speedNow: Double = 0.0): Trip {
        val rate = DriveOutlook.cruise(mps, tempC) * k
        val carried = DriveOutlook.REGEN * DriveOutlook.kineticWh(mps)
        val warm = if (km > warmKm) warmupWh + rate * warmKm + carried else 0.0
        val wh = if (km > warmKm) warm + rate * (km - warmKm) - carried + DriveOutlook.REGEN * DriveOutlook.kineticWh(speedNow)
            else rate * km + warmupWh * km / warmKm
        val seconds = km * 1000 / mps
        return Trip(Trip.Kind.DRIVE, t0, t0 + (seconds * 1000).toLong(), 0, 60_000.0, 60_000.0 - wh,
            lat0 = from?.first, lon0 = from?.second, lat1 = to?.first, lon1 = to?.second,
            distM = km * 1000, disWh = wh, tempC = tempC, movingS = seconds,
            warmM = if (km > warmKm) TripRecorder.WARM_M else 0.0, warmWh = warm, open = open)
    }

    @Test fun theModelIsAboutThreeMilesPerKwhAtSeventyAndColdCostsMore() {
        val at70 = TripStats.milesPerKwh(DriveOutlook.cruise(31.3, 18.0))
        assertTrue("$at70", at70 in 2.6..3.2)
        assertTrue(DriveOutlook.cruise(31.0, 18.0) > DriveOutlook.cruise(15.0, 18.0) * 1.5)
        assertEquals(DriveOutlook.cruise(15.0, 18.0) + 10 * DriveOutlook.COLD_WH_KM_PER_C, DriveOutlook.cruise(15.0, 8.0), 0.001)
        // Warmer than mild is not cheaper, and an unknown temperature is taken as mild.
        assertEquals(DriveOutlook.cruise(15.0, 18.0), DriveOutlook.cruise(15.0, 30.0), 0.001)
        assertEquals(DriveOutlook.cruise(15.0, 18.0), DriveOutlook.cruise(15.0, null), 0.001)
    }

    @Test fun aDriveIsReducedToOneNumberWhateverItsSpeedOrTheWeather() {
        val slow = DriveOutlook.sample(drive(day, 20.0, 12.0, 1.2, tempC = 2.0))!!
        val fast = DriveOutlook.sample(drive(day, 20.0, 30.0, 1.2, tempC = 25.0, warmupWh = 400.0))!!
        assertEquals(1.2, slow.k, 0.001)
        assertEquals(1.2, fast.k, 0.001)
        assertEquals(DriveOutlook.WARMUP_WH, slow.warmupWh, 0.01)
        assertEquals(400.0, fast.warmupWh, 0.01)
        // Too little road after the warm-up, or still under way: not a sample.
        assertNull(DriveOutlook.sample(drive(day, 4.0, 12.0, 1.2)))
        assertNull(DriveOutlook.sample(drive(day, 20.0, 12.0, 1.2, open = true)))
    }

    @Test fun withNoHistoryTheBareModelGivesAnArrivalAndNoVerdict() {
        val o = DriveOutlook.outlook(DriveOutlook.Input(null, 50_000.0, 85_000.0, 10.0, aheadM = 50_000.0, aheadS = 2000.0), null)
        assertEquals(DriveOutlook.Basis.MODEL, o.baseline.basis)
        val a = o.arrival!!
        assertEquals(25.0, a.speedMps, 0.001)
        assertEquals(DriveOutlook.cruise(25.0, 10.0) * 50 + DriveOutlook.WARMUP_WH, a.energyWh, 0.01)
        assertEquals((50_000.0 - a.energyWh) / 85_000.0 * 100, a.pct, 0.001)
        assertNull(o.verdict)
    }

    @Test fun noRouteMeansNoArrival() {
        val o = DriveOutlook.outlook(DriveOutlook.Input(drive(day, 8.0, 15.0, 1.0, open = true), 50_000.0, 85_000.0, 10.0), null)
        assertNull(o.arrival)
        assertNotNull(o.whPerKm)
    }

    @Test fun theEstimateForAWholeDriveIsWhatSuchADriveUsed() {
        val log = (1..6).map { drive(it * day, 30.0, 20.0, 1.10, warmupWh = 300.0) }
        val h = DriveOutlook.History(log)
        val o = DriveOutlook.outlook(DriveOutlook.Input(null, 50_000.0, 85_000.0, 18.0, aheadM = 30_000.0, aheadS = 1500.0), h)
        assertEquals(DriveOutlook.Basis.SIMILAR, o.baseline.basis)
        assertEquals(log[0].netWh, o.arrival!!.energyWh, 1.0)
    }

    @Test fun midDriveTheWarmUpIsSpentAndTheCarsMotionIsCredited() {
        val h = DriveOutlook.History((1..6).map { drive(it * day, 30.0, 20.0, 1.0) })
        // 12 km into a 30 km drive, still doing 20 m/s: what is left is 18 km at the cruise rate less the motion.
        val now = drive(9 * day, 12.0, 20.0, 1.0, open = true, speedNow = 20.0)
        val o = DriveOutlook.outlook(DriveOutlook.Input(now, 50_000.0, 85_000.0, 18.0, speedMps = 20.0, aheadM = 18_000.0, aheadS = 900.0), h)
        assertEquals(1.0, o.kNow!!, 0.001)
        assertEquals(DriveOutlook.cruise(20.0, 18.0) * 18 - DriveOutlook.REGEN * DriveOutlook.kineticWh(20.0), o.arrival!!.energyWh, 0.5)
        // The whole drive adds up: what was used so far plus what is ahead is what such a drive uses.
        assertEquals(drive(9 * day, 30.0, 20.0, 1.0).netWh, now.netWh + o.arrival!!.energyWh, 1.0)
        // One kilometre in, two thirds of the warm-up is still to come.
        val early = drive(9 * day, 1.0, 20.0, 1.0, open = true)
        val e = DriveOutlook.outlook(DriveOutlook.Input(early, 50_000.0, 85_000.0, 18.0, aheadM = 29_000.0, aheadS = 1450.0), h)
        assertEquals(DriveOutlook.cruise(20.0, 18.0) * 29 + DriveOutlook.WARMUP_WH * 2 / 3, e.arrival!!.energyWh, 0.5)
    }

    @Test fun aBadDayCarriesIntoTheRoadAheadOnlySlowly() {
        val h = DriveOutlook.History((1..6).map { drive(it * day, 30.0, 20.0, 1.0) })
        val bad = drive(9 * day, 103.0, 20.0, 1.4, open = true)
        val o = DriveOutlook.outlook(DriveOutlook.Input(bad, 50_000.0, 85_000.0, 18.0, aheadM = 50_000.0, aheadS = 2500.0), h)
        assertEquals(1.4, o.kNow!!, 0.001)
        // 100 km past the warm-up is half of the most the drive so far may count: a quarter.
        assertEquals((1.0 * 0.75 + 1.4 * 0.25) * DriveOutlook.cruise(20.0, 18.0), o.arrival!!.whPerKm, 0.01)
        assertEquals(TripStats.Standing.WORSE, o.verdict!!.standing)
    }

    @Test fun aFasterRoadAheadCostsMoreThanTheSameDistanceInTown() {
        val h = DriveOutlook.History((1..6).map { drive(it * day, 30.0, 15.0, 1.0) })
        fun at(seconds: Double) = DriveOutlook.outlook(DriveOutlook.Input(null, 50_000.0, 85_000.0, 18.0, aheadM = 30_000.0, aheadS = seconds), h).arrival!!
        assertTrue(at(1000.0).energyWh > at(2000.0).energyWh * 1.4)
    }

    @Test fun theVerdictComparesSpeedForSpeedAndDegreeForDegree() {
        // The log is mild town driving; today is a cold motorway, used exactly as the model says for that.
        val h = DriveOutlook.History((1..6).map { drive(it * day, 30.0, 12.0, 1.0) })
        val today = drive(9 * day, 30.0, 30.0, 1.0, tempC = 0.0, open = true)
        assertTrue(TripStats.whPerKm(today)!! > 1.5 * DriveOutlook.cruise(12.0, 18.0))
        val o = DriveOutlook.outlook(DriveOutlook.Input(today, 50_000.0, 85_000.0, 0.0), h)
        assertEquals(TripStats.Standing.TYPICAL, o.verdict!!.standing)
    }

    @Test fun theVerdictWaitsForRoadAfterTheWarmUp() {
        val h = DriveOutlook.History((1..6).map { drive(it * day, 30.0, 15.0, 1.0) })
        val early = DriveOutlook.outlook(DriveOutlook.Input(drive(9 * day, 4.5, 15.0, 1.6, open = true), 50_000.0, 85_000.0, 18.0), h)
        assertNull(early.verdict)
        val later = DriveOutlook.outlook(DriveOutlook.Input(drive(9 * day, 5.5, 15.0, 1.6, open = true), 50_000.0, 85_000.0, 18.0), h)
        assertEquals(TripStats.Standing.WORSE, later.verdict!!.standing)
    }

    @Test fun aRepeatedRouteIsUsedWhenThePhonesRouteMatchesItsLength() {
        val runs = (1..4).map { drive(it * day, 22.0, 18.0, 1.30, from = home, to = work) }
        val others = (5..10).map { drive(it * day, 22.0, 18.0, 0.90) }
        val h = DriveOutlook.History(runs + others)
        val leaving = drive(12 * day, 2.0, 18.0, 1.3, from = home, open = true)
        val onRoute = DriveOutlook.outlook(DriveOutlook.Input(leaving, 50_000.0, 85_000.0, 18.0, aheadM = 20_000.0, aheadS = 1100.0), h)
        assertEquals(DriveOutlook.Basis.ROUTE, onRoute.baseline.basis)
        assertEquals(1.30, onRoute.baseline.k, 0.001)
        // Somewhere twice as far from the same door is not that route.
        val elsewhere = DriveOutlook.outlook(DriveOutlook.Input(leaving, 50_000.0, 85_000.0, 18.0, aheadM = 45_000.0, aheadS = 2400.0), h)
        assertTrue(elsewhere.baseline.basis != DriveOutlook.Basis.ROUTE)
    }

    @Test fun arrivingUnderTenPercentIsLow() {
        val o = DriveOutlook.outlook(DriveOutlook.Input(null, 12_000.0, 85_000.0, 10.0, aheadM = 40_000.0, aheadS = 1600.0), null)
        assertTrue(o.arrival!!.low)
        val gone = DriveOutlook.outlook(DriveOutlook.Input(null, 3_000.0, 85_000.0, 10.0, aheadM = 40_000.0, aheadS = 1600.0), null)
        assertTrue(gone.arrival!!.pct < 0)
    }
}
