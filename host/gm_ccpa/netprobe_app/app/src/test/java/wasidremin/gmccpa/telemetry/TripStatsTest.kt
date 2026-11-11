package wasidremin.gmccpa.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** [TripStats] on made-up records. Coordinates are an invented grid near 0°, 0°. */
class TripStatsTest {
    private val hour = 3_600_000L
    private val day = TripStats.DAY_MS

    /** 0.01° of latitude is about 1.1 km, so these four places are far outside one another's radius. */
    private val home = 0.00 to 0.00
    private val work = 0.20 to 0.00
    private val shop = 0.00 to 0.05
    private val gym = 0.05 to 0.05

    private fun drive(t0: Long, from: Pair<Double, Double>?, to: Pair<Double, Double>?, km: Double, whPerKm: Double,
                      tempC: Double? = 10.0, minutes: Int = 30): Trip =
        Trip(Trip.Kind.DRIVE, t0, t0 + minutes * 60_000L, 0, 60_000.0, 60_000.0 - km * whPerKm,
            lat0 = from?.first, lon0 = from?.second, lat1 = to?.first, lon1 = to?.second,
            distM = km * 1000, disWh = km * whPerKm * 1.4, regWh = km * whPerKm * 0.4, tempC = tempC)

    private fun charge(t0: Long, minutes: Int, b0: Double, b1: Double, kw: Double? = null, endState: Int? = null,
                       gap: Boolean = false): Trip =
        Trip(Trip.Kind.CHARGE, t0, t0 + minutes * 60_000L, 0, b0, b1, powerKw = kw, limitPct = 80.0, capWh = 86_380.0,
            endState = endState, gap = gap)

    @Test fun weeksStartOnMonday() {
        // 1970-01-01 was a Thursday.
        assertEquals(-3, TripStats.weekStart(0))
        assertEquals(-3, TripStats.weekStart(3))
        assertEquals(4, TripStats.weekStart(4))
        assertEquals(4, TripStats.weekStart(10))
        assertEquals(-10, TripStats.weekStart(-4))
    }

    @Test fun localDaysFollowTheCarsOffset() {
        val lateEvening = 3 * hour // 03:00 UTC is 22:00 the day before at UTC-5
        assertEquals(0, TripStats.epochDay(lateEvening, 0))
        assertEquals(-1, TripStats.epochDay(lateEvening, -300))
        assertEquals(22 * 60, TripStats.minuteOfDay(lateEvening, -300))
    }

    @Test fun routeLettersRunPastZ() {
        assertEquals("A", TripStats.letters(0))
        assertEquals("Z", TripStats.letters(25))
        assertEquals("AA", TripStats.letters(26))
        assertEquals("AB", TripStats.letters(27))
    }

    @Test fun aRouteIsNamedWhenItRepeatsAndTheWayBackSharesItsLetter() {
        val drives = listOf(
            drive(1 * day, home, work, 22.0, 170.0),
            drive(1 * day + 9 * hour, work, home, 22.3, 180.0),
            drive(2 * day, home, shop, 5.5, 200.0),
            drive(3 * day, home, work, 22.4, 160.0),
            drive(3 * day + 9 * hour, work, home, 21.9, 175.0),
            drive(4 * day, home, work, 21.8, 190.0),
        )
        val index = TripStats.routes(drives)
        assertEquals(listOf("Route A", "Route A back"), index.repeated.map { it.label })
        val a = index.repeated[0]
        assertEquals(3, a.runs.size)
        assertEquals(160.0, a.bestWhPerKm!!, 1e-6)
        assertEquals(170.0, a.typicalWhPerKm!!, 1e-6)
        assertEquals(190.0, a.worstWhPerKm!!, 1e-6)
        assertEquals(22_000.0, a.distM, 1e-6)
        assertEquals("Route A", index.routeOf(drives[3])!!.label)
        assertEquals("Route A back", index.routeOf(drives[1])!!.label)
        assertNull("a one-off is not a route", index.routeOf(drives[2]))
    }

    @Test fun aDetourBetweenTheSamePlacesIsADifferentRoute() {
        val drives = listOf(
            drive(1 * day, home, work, 22.0, 170.0),
            drive(2 * day, home, work, 31.0, 165.0),
            drive(3 * day, home, work, 22.2, 172.0),
            drive(4 * day, home, work, 30.5, 168.0),
        )
        val index = TripStats.routes(drives)
        assertEquals(2, index.repeated.size)
        assertEquals("Route A", index.routeOf(drives[0])!!.label)
        assertEquals("Route B", index.routeOf(drives[1])!!.label)
        assertFalse(index.routeOf(drives[0])!!.key == index.routeOf(drives[1])!!.key)
    }

    @Test fun aRouteKeepsItsKeyAndLetterAsTheLogGrows() {
        val early = listOf(
            drive(1 * day, home, work, 22.0, 170.0),
            drive(2 * day, home, work, 22.0, 170.0),
            drive(3 * day, home, shop, 5.0, 200.0),
        )
        val later = early + listOf(
            drive(4 * day, gym, shop, 7.0, 190.0),
            drive(5 * day, gym, shop, 7.0, 190.0),
            drive(6 * day, home, shop, 5.0, 200.0),
        )
        val before = TripStats.routes(early).routeOf(early[0])!!
        val after = TripStats.routes(later)
        assertEquals(before.key, after.routeOf(early[0])!!.key)
        assertEquals("Route A", after.routeOf(early[0])!!.label)
        assertEquals("Route B", after.routeOf(later[3])!!.label)
        assertEquals("Route C", after.routeOf(later[2])!!.label)
    }

    @Test fun drivesWithoutAFixHaveNoRoute() {
        val drives = listOf(drive(1 * day, null, null, 22.0, 170.0), drive(2 * day, null, null, 22.0, 170.0))
        assertTrue(TripStats.routes(drives).repeated.isEmpty())
    }

    @Test fun aDriveIsFirstComparedWithItsOwnRouteAtTodaysTemperature() {
        // 2 Wh/km more for every degree colder, across 20 degrees.
        val runs = (0..5).map { i -> drive((i + 1) * day, home, work, 22.0, 200.0 - 2.0 * (i * 4), tempC = i * 4.0) }
        val today = drive(10 * day, home, work, 22.0, 190.0, tempC = 10.0)
        val all = runs + today
        val e = TripStats.expected(today, all, TripStats.routes(all))!!
        assertEquals(TripStats.Basis.ROUTE_AT_TEMPERATURE, e.basis)
        assertEquals(180.0, e.whPerKm, 1e-6)
        // Outside the temperatures seen, the line is not extended.
        val arctic = drive(11 * day, home, work, 22.0, 260.0, tempC = -30.0)
        val all2 = runs + arctic
        assertEquals(200.0, TripStats.expected(arctic, all2, TripStats.routes(all2))!!.whPerKm, 1e-6)
    }

    @Test fun withoutEnoughWeatherTheRouteMedianIsUsedThenDrivesOfTheSameLength() {
        val runs = listOf(160.0, 170.0, 190.0).mapIndexed { i, e -> drive((i + 1) * day, home, work, 22.0, e) }
        val today = drive(10 * day, home, work, 22.0, 150.0)
        val all = runs + today
        val e = TripStats.expected(today, all, TripStats.routes(all))!!
        assertEquals(TripStats.Basis.ROUTE, e.basis)
        assertEquals(170.0, e.whPerKm, 1e-6)

        val errands = (1..5).map { i -> drive(i * day, null, null, 6.0 + i, 200.0 + i) }
        val oneOff = drive(9 * day, home, gym, 9.0, 210.0)
        val e2 = TripStats.expected(oneOff, errands + oneOff, TripStats.routes(errands + oneOff))!!
        assertEquals(TripStats.Basis.LENGTH, e2.basis)
        assertEquals(203.0, e2.whPerKm, 1e-6)
        assertNull("nothing to compare a first long drive with",
            TripStats.expected(drive(9 * day, null, null, 80.0, 180.0), errands, TripStats.routes(errands)))
    }

    @Test fun theVerdictIsInMilesPerKwhTerms() {
        assertEquals(TripStats.Standing.TYPICAL, TripStats.verdict(172.0, 170.0)!!.standing)
        val better = TripStats.verdict(150.0, 180.0)!!
        assertEquals(TripStats.Standing.BETTER, better.standing)
        assertEquals(0.20, better.fraction, 1e-9)
        val worse = TripStats.verdict(200.0, 180.0)!!
        assertEquals(TripStats.Standing.WORSE, worse.standing)
        assertEquals(0.10, worse.fraction, 1e-9)
        assertEquals("a net gain on a downhill run", TripStats.Standing.BETTER, TripStats.verdict(-5.0, 180.0)!!.standing)
    }

    @Test fun theEnergySplitAddsUpToTheDrive() {
        // 20 km: the first 3 km used 900 Wh, the remaining 17 km used 2,550 Wh (150 Wh/km), 150 Wh while stopped.
        val t = Trip(Trip.Kind.DRIVE, 0, hour, 0, 60_000.0, 56_400.0, distM = 20_000.0, disWh = 5_000.0, regWh = 1_400.0,
            standWh = 150.0, warmM = 3_000.0, warmWh = 900.0)
        val s = TripStats.split(t)
        assertEquals(3_600.0, s.netWh, 1e-6)
        assertEquals(150.0, s.standingWh, 1e-6)
        // The later rate is (3,600 − 900) / 17 km = 158.8 Wh/km, so the first 3 km cost 423.5 Wh more than that.
        assertEquals(900.0 - 2_700.0 / 17.0 * 3.0, s.warmupWh, 1e-6)
        assertEquals(s.netWh, s.drivingWh + s.warmupWh + s.standingWh, 1e-6)
        assertEquals(0.28, s.regenShare!!, 1e-9)
    }

    @Test fun aShortOrDownhillDriveHasNoWarmUpShare() {
        val short = Trip(Trip.Kind.DRIVE, 0, hour, 0, 60_000.0, 59_300.0, distM = 3_500.0, disWh = 900.0, regWh = 200.0,
            warmM = 3_000.0, warmWh = 650.0)
        assertEquals(0.0, TripStats.split(short).warmupWh, 1e-9)
        // 5 km leaves only 2 km after the opening 3 km: too little road to compare against.
        val brief = Trip(Trip.Kind.DRIVE, 0, hour, 0, 60_000.0, 59_000.0, distM = 5_000.0, disWh = 1_200.0, regWh = 200.0,
            warmM = 3_000.0, warmWh = 800.0)
        assertEquals(0.0, TripStats.split(brief).warmupWh, 1e-9)
        assertEquals(1_000.0, TripStats.split(brief).drivingWh, 1e-9)
        val downhill = Trip(Trip.Kind.DRIVE, 0, hour, 0, 60_000.0, 60_200.0, distM = 9_000.0, disWh = 400.0, regWh = 600.0,
            standWh = 40.0, warmM = 3_000.0, warmWh = 100.0)
        val s = TripStats.split(downhill)
        assertEquals(-200.0, s.netWh, 1e-9)
        assertEquals(0.0, s.warmupWh, 1e-9)
        assertEquals(0.0, s.standingWh, 1e-9)
    }

    @Test fun rollupsCountShortTripsAndWeeks() {
        val monday = 4L // 1970-01-05
        val drives = listOf(
            drive(monday * day + 8 * hour, home, work, 22.0, 170.0),
            drive(monday * day + 17 * hour, work, home, 22.0, 180.0),
            drive((monday + 2) * day + 12 * hour, home, shop, 5.0, 220.0),
            drive((monday + 7) * day + 8 * hour, home, work, 22.0, 165.0),
        )
        val week = TripStats.rollup(TripStats.inWeek(drives, monday))
        assertEquals(3, week.drives)
        assertEquals(49_000.0, week.distM, 1e-6)
        assertEquals(1, week.shortTrips)
        assertEquals((22 * 170 + 22 * 180 + 5 * 220) / 49.0, week.whPerKm!!, 1e-6)
        assertEquals(2, TripStats.rollup(TripStats.onDay(drives, monday)).drives)
        val strip = TripStats.dailyDistance(drives, monday + 7, 8)
        assertEquals(8, strip.size)
        assertEquals(monday to 44_000.0, strip.first())
        assertEquals(0.0, strip[1].second, 0.0)
        assertEquals(22_000.0, strip.last().second, 1e-6)
    }

    @Test fun personalBestsIgnoreShortHops() {
        val drives = listOf(
            drive(1 * day, home, work, 22.0, 170.0),
            drive(2 * day, home, shop, 2.0, 90.0),
            drive(3 * day, home, work, 40.0, 150.0),
            drive(3 * day + 5 * hour, work, home, 40.0, 180.0),
        )
        val b = TripStats.bests(drives)
        assertEquals(drives[2].t0, b.efficiency!!.t0)
        assertEquals(drives[2].t0, b.longest!!.t0)
        assertEquals(3L to 80_000.0, b.biggestDay)
    }

    @Test fun chargeRecordsMinutesApartAreOnePlugIn() {
        val trips = listOf(
            // Slept through the first part, then watched the rest finish.
            charge(1 * day, 120, 30_000.0, 43_000.0, gap = true),
            charge(1 * day + 2 * hour + 60_000, 180, 43_000.0, 62_800.0, kw = 6.6, endState = 1),
            charge(1 * day + 5 * hour + 10 * 60_000, 60, 62_800.0, 69_100.0, kw = 6.3, endState = 2),
            // The next evening, unplugged early.
            charge(2 * day, 60, 50_000.0, 56_600.0, kw = 6.6, endState = 3),
        )
        val s = TripStats.sessions(trips)
        assertEquals(2, s.size)
        assertEquals(39_100.0, s[0].addedWh, 1e-6)
        assertTrue(s[0].slept)
        assertTrue(s[0].reachedLimit)
        assertEquals((6.6 * 180 + 6.3 * 60) / 240, s[0].powerKw!!, 1e-9)
        assertEquals("the watched part's start, not the sleep", trips[1].t0, s[0].t0)
        assertFalse(s[1].reachedLimit)
        assertFalse(s[1].slept)
    }

    @Test fun reachingTheLimitIsReadFromTheLevelWhenTheCarNeverSaidComplete() {
        val s = TripStats.sessions(listOf(charge(day, 300, 40_000.0, 0.80 * 86_380.0 - 500.0, kw = 6.5, endState = 3)))
        assertTrue(s.single().reachedLimit)
    }

    @Test fun monthsCollectChargingAndDrivingNewestFirst() {
        val jan20 = 19 * day
        val feb2 = 32 * day
        val sessions = TripStats.sessions(listOf(
            charge(jan20, 300, 30_000.0, 60_000.0, kw = 6.0, endState = 2),
            charge(feb2, 300, 40_000.0, 65_000.0, kw = 6.0, endState = 2),
        ))
        val drives = listOf(drive(jan20 + 12 * hour, home, work, 100.0, 170.0), drive(feb2 + 12 * hour, home, work, 50.0, 180.0))
        val m = TripStats.months(sessions, drives)
        assertEquals(listOf(1970 to 2, 1970 to 1), m.map { it.year to it.month })
        assertEquals(25_000.0, m[0].addedWh, 1e-6)
        assertEquals(1, m[0].sessions)
        assertEquals(100_000.0, m[1].distM, 1e-6)
    }

    @Test fun costCountsChargerLossAndMpgeMatchesTheSticker() {
        // 9 kWh into the battery is 10 kWh from the wall.
        assertEquals(10.0, TripStats.wallKwh(9_000.0), 1e-9)
        assertEquals(1.50, TripStats.cost(9_000.0, 0.15), 1e-9)
        val whPerKm = 1_000_000.0 / TripStats.M_PER_MILE / 3.68 // 3.68 mi/kWh
        assertEquals(3.68, TripStats.milesPerKwh(whPerKm), 1e-9)
        assertEquals(3.68 * 0.9 * 33.705, TripStats.mpge(whPerKm), 1e-6)
        assertEquals(10.0, TripStats.gasCost(100 * TripStats.M_PER_MILE, 3.0, 30.0), 1e-9)
    }

    /**
     * Numbers only, from the log `TripLogTest.replaysARecordedStreamWhenAsked` wrote. Skipped unless
     * `TRIP_STATS_LOG` names the directory holding that `trips.ndjson`.
     */
    @Test fun summarizesARecordedLogWhenAsked() {
        val dir = System.getenv("TRIP_STATS_LOG")
        assumeTrue(!dir.isNullOrBlank())
        val trips = TripStore(File(dir!!)).load()
        val drives = trips.filter { it.kind == Trip.Kind.DRIVE }
        val index = TripStats.routes(drives)
        val sessions = TripStats.sessions(trips)
        println("stats: ${drives.size} drives, ${drives.count { it.distM < TripStats.SHORT_TRIP_M }} short, " +
            "${index.repeated.size} repeated routes, ${sessions.size} charge sessions, " +
            "${sessions.count { it.reachedLimit }} to the limit")
        for (r in index.repeated.take(6)) {
            val fit = TripStats.tempFit(r.runs)
            println("stats: ${r.label}: ${r.runs.size} runs, ${"%.1f".format(r.distM / 1000)} km, " +
                "${"%.0f/%.0f/%.0f".format(r.bestWhPerKm, r.typicalWhPerKm, r.worstWhPerKm)} Wh/km" +
                (fit?.let { ", ${"%.2f".format(it.slopeWhPerKmPerC)} Wh/km per C" } ?: ""))
        }
        val bases = drives.groupingBy { TripStats.expected(it, drives, index)?.basis?.name ?: "none" }.eachCount()
        println("stats: expectation basis $bases")
        val standing = drives.mapNotNull { d ->
            val e = TripStats.expected(d, drives, index) ?: return@mapNotNull null
            TripStats.verdict(TripStats.whPerKm(d) ?: return@mapNotNull null, e.whPerKm)?.standing
        }.groupingBy { it.name }.eachCount()
        println("stats: verdicts $standing")
        val splits = drives.map { TripStats.split(it) }
        println("stats: warm-up ${"%.1f".format(splits.sumOf { it.warmupWh } / 1000)} kWh, " +
            "standing ${"%.1f".format(splits.sumOf { it.standingWh } / 1000)} kWh of " +
            "${"%.1f".format(splits.sumOf { it.netWh } / 1000)} kWh")
        for (m in TripStats.months(sessions, drives)) {
            println("stats: ${m.year}-${m.month}: ${m.sessions} charges, ${"%.1f".format(m.addedWh / 1000)} kWh added, " +
                "${"%.0f".format(m.distM / 1000)} km, ${"%.1f".format(m.netWh / 1000)} kWh driven")
        }
        assertNotNull(TripStats.bests(drives).longest)
    }
}
