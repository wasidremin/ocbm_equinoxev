package wasidremin.gmccpa.telemetry

import kotlin.math.abs

/**
 * The drive in progress, looked at two ways for the driver display's Drive card
 * ([wasidremin.gmccpa.nav.DriveCard]): the battery it will arrive with when the phone has a route,
 * and how it is going against what this car normally does.
 *
 * ## The model, and where it came from
 * A month of this car's own reports (2026-10-13 to 2026-11-12: 120 drives, 1,863 km) was replayed
 * through [TripRecorder] (`HistoryReplayTest`), and three things stood out:
 * - The first [TripRecorder.WARM_M] of a drive cost about 230 Wh more than the same distance later,
 *   warm day or cold. That is a fixed cost per drive ([WARMUP_WH]), not a rate.
 * - After it, energy per kilometre followed the average moving speed and the outside temperature
 *   closely: [cruise], a least-squares fit, with a median miss of 16 Wh/km against 27 for one flat
 *   figure. Its speed term came out at the air drag a 2.6 m2 car of this shape should have.
 * - A moving car holds energy it gets most of back when it stops ([kineticWh], [REGEN]). Leaving
 *   that out made every mid-drive estimate too high, by 18% half way and more near the end.
 *
 * A drive is reduced to `k`: its after-warm-up rate over what [cruise] says for its speed and
 * temperature. `k` is near 1 by construction; the drive log's median `k` ([baseline]) follows the
 * car as tyres, load and seasons move it. What lies ahead is then `k` x [cruise] at the speed the
 * phone expects (its distance remaining over its time remaining, which counts traffic), plus what
 * is left of the warm-up, less the energy the car is carrying.
 *
 * Checked the same way, with only earlier drives as history: at the start of the 49 drives of 8 km
 * or more the energy ahead was off by a median of 7.3% (0.5 points of the pack, 2.3 at worst),
 * against 10.1% for a flat average; half way through, 16% of what was left (0.4 points).
 * The drive so far says little about the rest at these lengths, so it is given weight only slowly
 * ([BLEND_HALF_M]); that matters on a long motorway run in rain or a headwind.
 *
 * What it cannot see: hills and wind ahead, and how the speed is spread over the route (the phone
 * sends one time for all of it).
 *
 * Pure functions over [Trip] records; no file, no clock, no Android.
 */
object DriveOutlook {
    /** Wh/km at a crawl on a mild day: rolling resistance, the drivetrain and what the car draws for itself. */
    const val BASE_WH_KM = 95.0
    /** Air drag, Wh/km per (m/s)^2. */
    const val AERO_WH_KM_PER_MPS2 = 0.138
    /** Each degree below [MILD_C] costs this much more per kilometre: cabin heat, a cold pack, dense air. */
    const val COLD_WH_KM_PER_C = 3.55
    const val MILD_C = 18.0
    /** What a drive's opening stretch costs beyond its distance at the cruise rate. */
    const val WARMUP_WH = 230.0
    /** The car with one person in it, and the share of its motion that braking puts back in the pack. */
    const val MASS_KG = 2400.0
    const val REGEN = 0.7
    const val MIN_SPEED_MPS = 3.0
    const val MAX_SPEED_MPS = 40.0

    /** Wh/km after the warm-up at an average moving speed of [vMps]. About 3.0 mi/kWh at 70 mph on a mild day. */
    fun cruise(vMps: Double, tempC: Double?): Double {
        val v = vMps.coerceIn(MIN_SPEED_MPS, MAX_SPEED_MPS)
        return BASE_WH_KM + AERO_WH_KM_PER_MPS2 * v * v + COLD_WH_KM_PER_C * (MILD_C - (tempC ?: MILD_C)).coerceAtLeast(0.0)
    }

    /** The energy of the car's motion at [vMps], in Wh. */
    fun kineticWh(vMps: Double): Double = 0.5 * MASS_KG * vMps * vMps / 3600.0

    /** A drive needs this much road after its warm-up for its rate to mean anything. */
    const val MIN_POST_M = 2000.0
    const val MIN_K = 0.3
    const val MAX_K = 3.0
    /** The drive so far counts for half of [BLEND_MAX] once it has come this far past its warm-up. */
    const val BLEND_HALF_M = 100_000.0
    const val BLEND_MAX = 0.5
    /** Arriving under this share of the pack is a warning. */
    const val LOW_ARRIVAL_PCT = 10.0
    /** With no speed from the phone and no history: a mixed drive. */
    const val DEFAULT_SPEED_MPS = 15.0

    enum class Basis { ROUTE, SIMILAR, ALL, MODEL }

    /** One finished drive as the estimate uses it. */
    class Sample(val k: Double, val warmupWh: Double, val speedMps: Double, val bucket: Int)

    /**
     * The after-warm-up rate of [t] so far, with the motion it still carries at [speedNowMps] taken
     * back out and the motion it carried into that stretch put back, so that a drive caught at speed
     * and one that has stopped read the same. Null until [MIN_POST_M] past the warm-up.
     */
    private fun cruiseRate(t: Trip, speedNowMps: Double): Double? {
        val post = t.distM - t.warmM
        if (t.warmM <= 0.0 || post < MIN_POST_M || t.movingS <= 0.0) return null
        val v = t.distM / t.movingS
        val rate = (t.netWh - t.warmWh + REGEN * (kineticWh(v) - kineticWh(speedNowMps))) / (post / 1000.0)
        return rate.takeIf { it.isFinite() && it > 0.0 }
    }

    fun sample(t: Trip): Sample? {
        if (t.kind != Trip.Kind.DRIVE || t.open) return null
        val rate = cruiseRate(t, 0.0) ?: return null
        val v = t.distM / t.movingS
        return Sample(
            k = (rate / cruise(v, t.tempC)).coerceIn(MIN_K, MAX_K),
            warmupWh = t.warmWh - rate * t.warmM / 1000.0 - REGEN * kineticWh(v),
            speedMps = v,
            bucket = TripStats.lengthBucket(t.distM),
        )
    }

    /** The drive log, read once: every usable drive as a [Sample], and the repeated routes. */
    class History(drives: List<Trip>) {
        val index: TripStats.RouteIndex = TripStats.routes(drives)
        val samples: List<Sample> = drives.mapNotNull { sample(it) }
        private val byRoute: Map<String, List<Sample>> =
            index.repeated.associate { r -> r.key to r.runs.mapNotNull { sample(it) } }

        /** The repeated route that leaves from here and is about [totalM] long, with its samples. */
        fun routeFrom(lat: Double?, lon: Double?, totalM: Double?): List<Sample>? {
            if (lat == null || lon == null || totalM == null) return null
            val r = index.repeated.filter { route ->
                abs(route.distM - totalM) <= TripStats.ROUTE_LENGTH_TOLERANCE * route.distM && route.runs.any { run ->
                    val la = run.lat0; val lo = run.lon0
                    la != null && lo != null && InsightsData.haversineM(la, lo, lat, lon) <= TripStats.PLACE_RADIUS_M
                }
            }.minByOrNull { abs(it.distM - totalM) } ?: return null
            return byRoute[r.key]
        }
    }

    /** What is normal for a drive like this one. [best] and [worst] are the range the samples span. */
    class Baseline(val k: Double, val warmupWh: Double, val speedMps: Double?, val basis: Basis, val runs: Int,
                   val best: Double, val worst: Double)

    /**
     * The normal `k` and warm-up for a drive of [totalM]: the route's own runs when the phone's
     * route matches one, else drives of about the same length, else every drive, else the bare model.
     */
    fun baseline(h: History?, startLat: Double?, startLon: Double?, totalM: Double?, routeKnown: Boolean): Baseline {
        if (h == null) return MODEL
        val route = if (routeKnown) h.routeFrom(startLat, startLon, totalM)?.takeIf { it.size >= 3 } else null
        val alike = totalM?.let { m -> h.samples.filter { it.bucket == TripStats.lengthBucket(m) } }?.takeIf { it.size >= 5 }
        val (set, basis) = when {
            route != null -> route to Basis.ROUTE
            alike != null -> alike to Basis.SIMILAR
            h.samples.size >= 3 -> h.samples to Basis.ALL
            else -> return MODEL
        }
        val ks = set.map { it.k }
        return Baseline(TripStats.median(ks)!!, TripStats.median(set.map { it.warmupWh })!!.coerceIn(0.0, 1000.0),
            TripStats.median(set.map { it.speedMps }), basis, set.size, ks.min(), ks.max())
    }

    private val MODEL = Baseline(1.0, WARMUP_WH, null, Basis.MODEL, 0, 1.0, 1.0)

    /** Everything known at this moment. [aheadM] and [aheadS] are the phone's, null with no route. */
    class Input(
        val drive: Trip?,
        val batteryWh: Double?,
        val capWh: Double?,
        val tempC: Double?,
        val speedMps: Double = 0.0,
        val aheadM: Double? = null,
        val aheadS: Double? = null,
    )

    class Arrival(
        /** Share of the pack left at the destination, in percent. Below zero: the car does not get there. */
        val pct: Double,
        val energyWh: Double,
        /** The cruise rate the estimate used for the road ahead, and the speed it took from the phone. */
        val whPerKm: Double,
        val speedMps: Double,
    ) {
        val low: Boolean get() = pct < LOW_ARRIVAL_PCT
    }

    class Outlook(
        /** The whole drive so far, Wh/km; null before it has gone anywhere or while it has gained energy. */
        val whPerKm: Double?,
        /** The drive since its warm-up against [baseline], speed for speed and degree for degree. */
        val verdict: TripStats.Verdict?,
        val baseline: Baseline,
        /** Today's `k`, where the drive so far sits in [Baseline.best]..[Baseline.worst]. */
        val kNow: Double?,
        val arrival: Arrival?,
    )

    fun outlook(i: Input, h: History?): Outlook {
        val d = i.drive?.takeIf { it.kind == Trip.Kind.DRIVE }
        val driven = d?.distM ?: 0.0
        val ahead = i.aheadM?.takeIf { it.isFinite() && it > 0.0 }
        val temp = i.tempC ?: d?.tempC
        val base = baseline(h, d?.lat0, d?.lon0, if (ahead != null) driven + ahead else driven.takeIf { it > 0.0 }, routeKnown = ahead != null)

        val speedSoFar = d?.takeIf { it.movingS > 0.0 }?.let { it.distM / it.movingS }
        val kNow = d?.let { cruiseRate(it, i.speedMps) }?.let { (it / cruise(speedSoFar!!, temp)).coerceIn(MIN_K, MAX_K) }
        val verdict = if (kNow != null && base.basis != Basis.MODEL) TripStats.verdict(kNow, base.k) else null

        val battery = i.batteryWh?.takeIf { it.isFinite() }
        val cap = i.capWh?.takeIf { it > 1000.0 }
        val arrival = if (ahead != null && battery != null && cap != null) {
            val post = (driven - (d?.warmM ?: 0.0)).coerceAtLeast(0.0)
            val w = if (kNow == null) 0.0 else BLEND_MAX * post / (post + BLEND_HALF_M)
            val k = base.k * (1 - w) + (kNow ?: base.k) * w
            val speed = (i.aheadS?.takeIf { it.isFinite() && it > 0.0 }?.let { ahead / it } ?: base.speedMps ?: speedSoFar
                ?: DEFAULT_SPEED_MPS).coerceIn(MIN_SPEED_MPS, MAX_SPEED_MPS)
            val per = k * cruise(speed, temp)
            val warmup = base.warmupWh * (1.0 - driven / TripRecorder.WARM_M).coerceAtLeast(0.0)
            val energy = (per * ahead / 1000.0 + warmup - REGEN * kineticWh(i.speedMps)).coerceAtLeast(0.0)
            Arrival((battery - energy) / cap * 100.0, energy, per, speed)
        } else null
        return Outlook(d?.let { TripStats.whPerKm(it) }?.takeIf { it.isFinite() && it > 0.0 }, verdict, base, kNow, arrival)
    }
}
