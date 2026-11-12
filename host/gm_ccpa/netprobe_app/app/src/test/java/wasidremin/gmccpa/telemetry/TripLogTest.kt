package wasidremin.gmccpa.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.TimeZone

/** [TripRecorder] and [TripStore] on made-up callback streams. Times are epoch milliseconds. */
class TripLogTest {
    private val out = mutableListOf<Trip>()
    private fun recorder() = TripRecorder(tzMin = { -300 }) { out += it }

    /** Drive at [mps] for [seconds], one speed sample a second and a battery sample every two. */
    private fun cruise(r: TripRecorder, from: Long, seconds: Int, mps: Double, battery0: Double, whPerS: Double): Long {
        var t = from
        for (i in 0..seconds) {
            t = from + i * 1000L
            r.onSpeed(t, mps)
            if (i % 2 == 0) r.onBattery(t, battery0 - whPerS * i)
        }
        return t
    }

    @Test fun aDriveRunsFromTheFirstToTheLastMovingSampleAndClosesAfterFourMinutesStill() {
        val r = recorder()
        r.onBattery(1_000, 60_000.0)
        r.onProperty(1_000, InsightsData.OUTSIDE_C, 10.0f)
        r.onProperty(1_000, InsightsData.RANGE_M, 300_000.0f)
        val end = cruise(r, 10_000, 600, 20.0, 60_000.0, 5.0)
        r.onSpeed(end + 1000, 0.0)
        r.tick(end + 200_000)
        assertTrue("still open inside four minutes", out.isEmpty())
        assertNotNull(r.openDrive())
        r.tick(end + 241_000)
        assertEquals(1, out.size)
        val d = out[0]
        assertEquals(Trip.Kind.DRIVE, d.kind)
        assertEquals(10_000L, d.t0)
        assertEquals(end, d.t1)
        assertEquals(12_000.0, d.distM, 1.0)
        assertEquals(3_000.0, d.netWh, 10.0)
        assertEquals(3_000.0, d.disWh, 10.0)
        assertEquals(0.0, d.regWh, 0.01)
        assertEquals(10.0, d.tempC!!, 0.01)
        assertEquals(-300, d.tzMin)
        assertEquals(600.0, d.movingS, 1.0)
        assertTrue("the opening 3 km is split out", d.warmM in 3000.0..3030.0)
        assertEquals(750.0, d.warmWh, 15.0)
        assertNull(r.openDrive())
    }

    @Test fun aShortStopStaysInsideTheDriveAndItsEnergyIsCountedAsStandstill() {
        val r = recorder()
        r.onBattery(0, 50_000.0)
        var t = cruise(r, 1_000, 100, 10.0, 50_000.0, 2.0)
        // Two minutes at a light, using 0.5 Wh a second.
        for (i in 1..120) {
            r.onSpeed(t + i * 1000L, 0.0)
            if (i % 2 == 0) r.onBattery(t + i * 1000L, 49_800.0 - 0.5 * i)
        }
        t = cruise(r, t + 121_000, 100, 10.0, 49_740.0, 2.0)
        r.tick(t + 300_000)
        assertEquals(1, out.size)
        val d = out[0]
        assertEquals(2_000.0, d.distM, 15.0)
        assertEquals(60.0, d.standWh, 3.0)
        assertEquals(200.0, d.movingS, 2.0)
        assertEquals(d.durationS - d.movingS, 121.0, 3.0)
    }

    @Test fun regenerationIsSummedApartFromWhatTheBatteryGaveUp() {
        val r = recorder()
        r.onBattery(0, 50_000.0)
        r.onSpeed(1_000, 15.0)
        val levels = listOf(49_900.0, 49_800.0, 49_850.0, 49_900.0, 49_700.0)
        levels.forEachIndexed { i, wh ->
            r.onSpeed(2_000 + i * 60_000L / 10, 15.0)
            r.onBattery(2_000 + i * 6_000L, wh)
            for (s in 1..5) r.onSpeed(2_000 + i * 6_000L + s * 1000L, 15.0)
        }
        for (s in 0..60) r.onSpeed(40_000 + s * 1000L, 15.0)
        r.tick(1_000_000)
        val d = out.single()
        // The step from the level before the drive to the first one inside it is not counted.
        assertEquals(300.0, d.disWh, 0.01)
        assertEquals(100.0, d.regWh, 0.01)
    }

    @Test fun aRollAcrossTheDrivewayIsNotADrive() {
        val r = recorder()
        r.onBattery(0, 50_000.0)
        cruise(r, 1_000, 40, 5.0, 50_000.0, 1.0)
        r.tick(900_000)
        assertTrue(out.isEmpty())
    }

    @Test fun aChargeIsTheLevelRisingWhileParkedWithPowerTakenFromTheLevel() {
        val r = recorder()
        r.onProperty(0, InsightsData.CHARGE_LIMIT_PCT, 80.0f)
        r.onProperty(0, InsightsData.CAPACITY_WH, 86_380.0f)
        r.onProperty(0, InsightsData.CHARGE_STATE, 1)
        // 6.6 kW for two hours: 110 Wh a minute.
        for (m in 0..120) r.onBattery(m * 60_000L, 55_000.0 + 110.0 * m)
        r.onProperty(121 * 60_000L, InsightsData.CHARGE_STATE, 2)
        r.onBattery(125 * 60_000L, 68_200.0)
        r.tick(140 * 60_000L)
        val c = out.single()
        assertEquals(Trip.Kind.CHARGE, c.kind)
        assertEquals(13_200.0, c.addedWh, 0.01)
        assertEquals(0L, c.t0)
        assertEquals(120 * 60_000L, c.t1)
        assertEquals(6.6, c.powerKw!!, 0.01)
        assertEquals(80.0, c.limitPct!!, 0.01)
        assertEquals(2, c.endState)
        assertFalse(c.gap)
    }

    @Test fun aChargeSleptThroughIsRecordedFromTheLevelBeforeAndAfter() {
        val r = recorder()
        r.onBattery(0, 50_000.0)
        r.onBattery(6 * 3_600_000L, 62_000.0)
        val c = out.single()
        assertTrue(c.gap)
        assertEquals(12_000.0, c.addedWh, 0.01)
        assertNull(c.powerKw)
        // A parked car losing charge over a gap is nothing.
        r.onBattery(30 * 3_600_000L, 61_500.0)
        assertEquals(1, out.size)
    }

    @Test fun aRestingBatteryDriftingUpIsNotACharge() {
        val r = recorder()
        r.onProperty(0, InsightsData.CHARGE_STATE, 3)
        r.onBattery(0, 50_000.0)
        // Across a sleep, and then slowly while awake: the car never says it is charging.
        r.onBattery(3 * 3_600_000L, 50_600.0)
        for (m in 1..30) r.onBattery(3 * 3_600_000L + m * 60_000L, 50_600.0 + 15.0 * m)
        r.tick(5 * 3_600_000L)
        assertTrue(out.isEmpty())
    }

    @Test fun aChargeThatBeganBeforeTheHeadUnitWokeStartsFromTheLevelItSleptAt() {
        val r = recorder()
        r.onProperty(0, InsightsData.CHARGE_STATE, 3)
        r.onBattery(0, 50_000.0)
        val wake = 8 * 3_600_000L
        r.onBattery(wake, 50_400.0)
        r.onProperty(wake + 500, InsightsData.CHARGE_STATE, 1)
        for (m in 1..60) r.onBattery(wake + m * 60_000L, 50_400.0 + 110.0 * m)
        r.onProperty(wake + 61 * 60_000L, InsightsData.CHARGE_STATE, 2)
        r.tick(wake + 2 * 3_600_000L)
        val c = out.single()
        assertEquals(50_000.0, c.b0, 0.01)
        assertEquals(7_000.0, c.addedWh, 0.01)
        assertEquals(wake, c.t0)
        assertEquals(6.6, c.powerKw!!, 0.01)
        assertEquals(2, c.endState)
    }

    @Test fun theLevelSettlingUpwardAtAStopLightIsNotACharge() {
        val r = recorder()
        r.onBattery(0, 50_000.0)
        val t = cruise(r, 1_000, 300, 20.0, 50_000.0, 4.0)
        r.onSpeed(t + 1000, 0.0)
        for (i in 1..20) r.onBattery(t + 1000 + i * 2000L, 48_800.0 + 20.0 * i)
        r.tick(t + 2_000_000)
        assertEquals(listOf(Trip.Kind.DRIVE), out.map { it.kind })
    }

    @Test fun aDriveKeepsTheFixItLeftFromAndTheLastFixWhileMoving() {
        val r = recorder()
        r.onBattery(0, 50_000.0)
        r.onLocation(500, 10.0, 20.0, 3.0, 100.0)
        r.onLocation(600, 55.0, 55.0, 400.0, null) // too coarse, dropped
        var t = 1_000L
        for (i in 0..200) {
            t = 1_000 + i * 1000L
            r.onSpeed(t, 12.0)
            r.onLocation(t, 10.0 + i * 0.0001, 20.0, 2.0, 100.0 + i * 0.2)
            if (i % 2 == 0) r.onBattery(t, 50_000.0 - i)
        }
        r.onSpeed(t + 1000, 0.0)
        r.onLocation(t + 5000, 10.5, 20.5, 2.0, 100.0) // after the last moving sample
        r.tick(t + 600_000)
        val d = out.single()
        assertEquals(10.0, d.lat0!!, 1e-9)
        assertEquals(10.02, d.lat1!!, 2e-4)
        assertEquals(40.0, d.upM!!, 5.01) // counted in 5 m steps
        assertEquals(0.0, d.downM!!, 0.01)
    }

    @Test fun aKilledProcessPicksTheDriveUpFromItsCheckpoint() {
        val a = recorder()
        a.onBattery(0, 50_000.0)
        val t = cruise(a, 1_000, 300, 20.0, 50_000.0, 4.0)
        val saved = a.checkpoint()
        assertNotNull(TripRecorder.openDriveOf(saved))
        // Restarted a minute later, still moving: one drive, the hole not counted as distance.
        val b = recorder()
        b.restore(saved, t + 60_000)
        val end = cruise(b, t + 60_000, 100, 20.0, 48_600.0, 4.0)
        b.tick(end + 300_000)
        val d = out.single()
        assertEquals(1_000L, d.t0)
        assertEquals(8_000.0, d.distM, 1.0)
        assertEquals(1_800.0, d.netWh, 10.0)
    }

    @Test fun aCheckpointLeftOverFromYesterdayIsClosedOnTheNextStart() {
        val a = recorder()
        a.onBattery(0, 50_000.0)
        val t = cruise(a, 1_000, 300, 20.0, 50_000.0, 4.0)
        val saved = a.checkpoint()
        val b = recorder()
        b.restore(saved, t + 12 * 3_600_000L)
        assertEquals(1, out.size)
        assertEquals(t, out[0].t1)
        // The level it left behind still lets a charge slept through be seen.
        b.onBattery(t + 12 * 3_600_000L + 1000, 60_000.0)
        assertEquals(listOf(Trip.Kind.DRIVE, Trip.Kind.CHARGE), out.map { it.kind })
    }

    @Test fun theStoreAppendsReloadsAndSurvivesATornLastLine() {
        val dir = Files.createTempDirectory("trips").toFile()
        try {
            val store = TripStore(dir)
            assertTrue(store.load().isEmpty())
            val d = Trip(Trip.Kind.DRIVE, 2_000, 9_000, -300, 50_000.0, 49_000.0, lat0 = 1.5, lon0 = 2.5, lat1 = 3.5, lon1 = 4.5,
                distM = 6_000.0, disWh = 1_200.0, regWh = 200.0, tempC = 11.5, range0M = 300_000.0, range1M = 290_000.0,
                movingS = 400.0, standWh = 30.0, warmM = 3_005.0, warmWh = 650.0, vmaxMps = 31.0, upM = 12.0, downM = 9.0)
            val c = Trip(Trip.Kind.CHARGE, 1_000, 1_500, -300, 40_000.0, 52_000.0, powerKw = 6.7, limitPct = 80.0,
                capWh = 86_380.0, endState = 2)
            store.append(d)
            store.append(c)
            File(dir, "trips.ndjson").appendText("{\"k\":\"d\",\"t0\":12")
            assertEquals(listOf(c, d), store.load())
            store.saveCheckpoint("{}")
            assertEquals("{}", store.loadCheckpoint())
            store.clear()
            assertTrue(store.load().isEmpty())
            assertNull(store.loadCheckpoint())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun theDriveJustFinishedShowsBeforeItIsWrittenOut() {
        val dir = Files.createTempDirectory("trips").toFile()
        try {
            val store = TripStore(dir)
            val r = TripRecorder(tzMin = { 0 }) { store.append(it) }
            r.onBattery(0, 50_000.0)
            val t = cruise(r, 1_000, 300, 20.0, 50_000.0, 4.0)
            store.saveCheckpoint(r.checkpoint())
            val shown = store.loadWithOpen().single()
            assertTrue(shown.open)
            assertEquals(6_000.0, shown.distM, 1.0)
            r.tick(t + 300_000)
            // Written out, and the stale checkpoint does not show it twice.
            assertEquals(1, store.loadWithOpen().size)
            assertFalse(store.loadWithOpen().single().open)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Bench only: replay a recorded callback stream (`TRIP_REPLAY_TSV`: `ms P id value` and
     * `ms G lat lon accuracy` lines) and write the resulting log to `TRIP_REPLAY_OUT`. Skipped
     * unless both are set; recorded drives never go into the repository.
     */
    @Test fun replaysARecordedStreamWhenAsked() {
        val src = System.getenv("TRIP_REPLAY_TSV")
        val dst = System.getenv("TRIP_REPLAY_OUT")
        assumeTrue(!src.isNullOrBlank() && !dst.isNullOrBlank())
        val tz = TimeZone.getDefault()
        val trips = mutableListOf<Trip>()
        val r = TripRecorder(tzMin = { tz.getOffset(it) / 60_000 }) { trips += it }
        var last = 0L
        File(src!!).forEachLine { line ->
            val f = line.split('\t')
            val t = f[0].toLong()
            last = t
            if (f[1] == "P") r.onProperty(t, f[2].toInt(), f[3].toDouble())
            else r.onLocation(t, f[2].toDouble(), f[3].toDouble(), f[4].toDouble(), null)
        }
        r.tick(last + 3_600_000L)
        File(dst!!).writeText(trips.joinToString("") { it.toJson().toString() + "\n" })
        val drives = trips.filter { it.kind == Trip.Kind.DRIVE }
        println("replay: ${drives.size} drives, ${"%.1f".format(drives.sumOf { it.distM } / 1000)} km, " +
            "${"%.1f".format(drives.sumOf { it.netWh } / 1000)} kWh net, " +
            "${trips.size - drives.size} charges")
    }

    @Test fun theLiveReadingCarriesTheBatteryEveryHundredMetres() {
        val r = recorder()
        r.onBattery(1_000, 60_000.0)
        val end = cruise(r, 10_000, 300, 20.0, 60_000.0, 5.0)   // 6 km
        val marks = r.live(end).marks
        assertTrue(marks.size in 55..62)
        assertTrue(marks.zipWithNext().all { (a, b) -> b.distM - a.distM >= RollingEfficiency.STEP_M - 1e-6 })
        assertTrue(marks.first().wh > marks.last().wh)
        // 5 Wh a second at 20 m/s is 250 Wh/km; the battery moves in the test's own steps, so roughly.
        assertEquals(250.0, RollingEfficiency.span(marks, 1000.0)!!.whPerKm, 15.0)
        r.tick(end + 300_000)
        assertTrue(r.live(end + 300_000).marks.isEmpty())
    }
}
