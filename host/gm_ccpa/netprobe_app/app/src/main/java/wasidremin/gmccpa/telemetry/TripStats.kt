package wasidremin.gmccpa.telemetry

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max

/**
 * What the Insights page says about the drive log ([Trip]): how a drive compares with the normal
 * for its route, which routes repeat, what charging costs, and how this week went. Pure functions
 * over a list of records; nothing here reads a file or a clock.
 *
 * Efficiency is carried as Wh/km (lower is better) and turned into mi/kWh only for display.
 */
object TripStats {
    /** Two drive ends closer than this are the same place. */
    const val PLACE_RADIUS_M = 250.0
    /** Two drives between the same places are the same route when their lengths agree this closely. */
    const val ROUTE_LENGTH_TOLERANCE = 0.12
    const val SHORT_TRIP_M = 8000.0
    /** Battery energy as a share of what the wall delivered: a home AC charger loses about a tenth. */
    const val CHARGER_EFFICIENCY = 0.90
    /** The EPA's energy content of a US gallon of gasoline, for MPGe. */
    const val KWH_PER_GALLON = 33.705
    const val M_PER_MILE = 1609.344
    /** Charge records this close together are one plug-in. */
    const val CHARGE_JOIN_MS = 20 * 60_000L
    const val DAY_MS = 86_400_000L
    /** Efficiency within this of the expectation is "typical". */
    const val TYPICAL_BAND = 0.05

    fun whPerKm(t: Trip): Double? =
        if (t.kind == Trip.Kind.DRIVE && t.distM >= 100.0) t.netWh / (t.distM / 1000.0) else null

    /** Days since 1970-01-01 on the car's local calendar. */
    fun epochDay(t: Long, tzMin: Int): Long = Math.floorDiv(t + tzMin * 60_000L, DAY_MS)
    fun dayOf(t: Trip): Long = epochDay(t.t1, t.tzMin)
    /** The Monday on or before [day]. */
    fun weekStart(day: Long): Long = day - Math.floorMod(day + 3, 7L)
    fun minuteOfDay(t: Long, tzMin: Int): Int = (Math.floorMod(t + tzMin * 60_000L, DAY_MS) / 60_000L).toInt()

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    // ---------------------------------------------------------------------------------------------
    // Routes
    // ---------------------------------------------------------------------------------------------

    class Route(
        /** Stable for an append-only log: place numbers and length groups are assigned in time order. */
        val key: String,
        /** "Route A", or "Route A back" for the same road the other way. */
        val label: String,
        val runs: List<Trip>,
    ) {
        val distM: Double = median(runs.map { it.distM }) ?: 0.0
        private val rates = runs.mapNotNull { whPerKm(it) }
        val bestWhPerKm: Double? = rates.minOrNull()
        val typicalWhPerKm: Double? = median(rates)
        val worstWhPerKm: Double? = rates.maxOrNull()
    }

    class RouteIndex(
        /** Routes driven at least twice, most driven first. */
        val repeated: List<Route>,
        private val keyByDrive: Map<Long, String>,
        private val byKey: Map<String, Route>,
    ) {
        /** The repeated route [drive] belongs to, or null for a one-off or a drive without a GPS fix. */
        fun routeOf(drive: Trip): Route? = keyByDrive[drive.t0]?.let { byKey[it] }
        fun route(key: String?): Route? = byKey[key]
    }

    fun routes(drives: List<Trip>): RouteIndex {
        class Anchor(val lat: Double, val lon: Double)
        class Group(val key: String, val from: Int, val to: Int, val distM: Double) {
            val runs = mutableListOf<Trip>()
            var label: String? = null
        }
        val anchors = mutableListOf<Anchor>()
        // The first point seen names the place and never moves, so numbering cannot shift later.
        fun place(lat: Double, lon: Double): Int {
            var best = -1
            var bestD = PLACE_RADIUS_M
            anchors.forEachIndexed { i, a ->
                val d = InsightsData.haversineM(a.lat, a.lon, lat, lon)
                if (d <= bestD) { best = i; bestD = d }
            }
            if (best >= 0) return best
            anchors += Anchor(lat, lon)
            return anchors.size - 1
        }
        val groups = mutableListOf<Group>()
        val keyByDrive = HashMap<Long, String>()
        var lettered = 0
        for (d in drives.filter { it.kind == Trip.Kind.DRIVE && !it.open }.sortedBy { it.t0 }) {
            val la0 = d.lat0 ?: continue
            val lo0 = d.lon0 ?: continue
            val la1 = d.lat1 ?: continue
            val lo1 = d.lon1 ?: continue
            val from = place(la0, lo0)
            val to = place(la1, lo1)
            val same = groups.filter { it.from == from && it.to == to }
            val g = same.firstOrNull { abs(it.distM - d.distM) <= ROUTE_LENGTH_TOLERANCE * it.distM }
                ?: Group("$from>$to#${same.size}", from, to, d.distM).also { groups += it }
            g.runs += d
            keyByDrive[d.t0] = g.key
            if (g.runs.size == 2) {
                // Lettered in the order routes first repeat, so a label never moves to another route.
                val there = groups.firstOrNull {
                    it !== g && it.from == to && it.to == from && it.label != null && !it.label!!.endsWith(" back") &&
                        abs(it.distM - g.distM) <= ROUTE_LENGTH_TOLERANCE * it.distM &&
                        groups.none { o -> o.label == it.label + " back" }
                }
                g.label = if (there != null) there.label + " back" else "Route " + letters(lettered++)
            }
        }
        val all = groups.filter { it.label != null }.map { Route(it.key, it.label!!, it.runs.toList()) }
        return RouteIndex(
            repeated = all.sortedWith(compareByDescending<Route> { it.runs.size }.thenBy { it.label }),
            keyByDrive = keyByDrive.filterValues { k -> all.any { it.key == k } },
            byKey = all.associateBy { it.key },
        )
    }

    /** 0 → A, 25 → Z, 26 → AA. */
    internal fun letters(index: Int): String {
        var n = index
        val sb = StringBuilder()
        do { sb.insert(0, 'A' + n % 26); n = n / 26 - 1 } while (n >= 0)
        return sb.toString()
    }

    // ---------------------------------------------------------------------------------------------
    // What a drive should have used
    // ---------------------------------------------------------------------------------------------

    enum class Basis { ROUTE_AT_TEMPERATURE, ROUTE, LENGTH }

    class Expectation(val whPerKm: Double, val basis: Basis, val runs: Int)

    /** The slope of a route's efficiency against outside temperature, when its runs span enough of it. */
    class TempFit(val slopeWhPerKmPerC: Double, val interceptWhPerKm: Double, val minC: Double, val maxC: Double, val runs: Int) {
        fun at(tempC: Double): Double = interceptWhPerKm + slopeWhPerKmPerC * tempC.coerceIn(minC, maxC)
    }

    fun tempFit(runs: List<Trip>): TempFit? {
        val pts = runs.mapNotNull { r -> val t = r.tempC; val e = whPerKm(r); if (t != null && e != null) t to e else null }
        if (pts.size < 5) return null
        val minC = pts.minOf { it.first }
        val maxC = pts.maxOf { it.first }
        if (maxC - minC < 6.0) return null
        val mx = pts.sumOf { it.first } / pts.size
        val my = pts.sumOf { it.second } / pts.size
        val sxx = pts.sumOf { (it.first - mx) * (it.first - mx) }
        if (sxx <= 0.0) return null
        val slope = pts.sumOf { (it.first - mx) * (it.second - my) } / sxx
        return TempFit(slope, my - slope * mx, minC, maxC, pts.size)
    }

    private fun lengthBucket(distM: Double): Int = when {
        distM < 5_000 -> 0
        distM < 15_000 -> 1
        distM < 40_000 -> 2
        else -> 3
    }

    /**
     * What [drive] would normally use, from the other drives only: its own route at today's
     * temperature when the route has enough runs across enough weather, else the route's median,
     * else the median of drives of about the same length. Null until there is enough history.
     */
    fun expected(drive: Trip, drives: List<Trip>, index: RouteIndex): Expectation? {
        val others = drives.filter { it.kind == Trip.Kind.DRIVE && !it.open && it.t0 != drive.t0 }
        val route = index.routeOf(drive)
        if (route != null) {
            val runs = route.runs.filter { it.t0 != drive.t0 }
            val temp = drive.tempC
            if (temp != null) tempFit(runs)?.let { return Expectation(it.at(temp), Basis.ROUTE_AT_TEMPERATURE, it.runs) }
            if (runs.size >= 3) median(runs.mapNotNull { whPerKm(it) })?.let { return Expectation(it, Basis.ROUTE, runs.size) }
        }
        val alike = others.filter { lengthBucket(it.distM) == lengthBucket(drive.distM) }.mapNotNull { whPerKm(it) }
        if (alike.size >= 5) return Expectation(median(alike)!!, Basis.LENGTH, alike.size)
        return null
    }

    enum class Standing { BETTER, TYPICAL, WORSE }

    /** How far [whPerKm] is from [expected], as a fraction of it (0.08 = 8% away). */
    class Verdict(val standing: Standing, val fraction: Double)

    fun verdict(whPerKm: Double, expected: Double): Verdict? {
        if (expected <= 0.0 || !whPerKm.isFinite()) return null
        // Reported in mi/kWh terms, the way the number is shown: 10% more miles per kWh is "10% better".
        val gain = expected / whPerKm - 1.0
        return when {
            whPerKm <= 0.0 -> Verdict(Standing.BETTER, 1.0)
            abs(gain) < TYPICAL_BAND -> Verdict(Standing.TYPICAL, abs(gain))
            gain > 0 -> Verdict(Standing.BETTER, gain)
            else -> Verdict(Standing.WORSE, -gain)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Where a drive's energy went
    // ---------------------------------------------------------------------------------------------

    /**
     * The drive's net energy in three parts that add up to it, plus what regeneration put back.
     * Warm-up is how much more the opening stretch used than the same distance did later in the
     * drive (cabin and battery heating, cold tyres). It is 0 when the later stretch is shorter than
     * the opening one: a mile of road after the warm-up says more about that mile than about the car.
     */
    class EnergySplit(val netWh: Double, val drivingWh: Double, val warmupWh: Double, val standingWh: Double,
                      val regenWh: Double, val grossWh: Double) {
        val regenShare: Double? get() = if (grossWh > 0.0) regenWh / grossWh else null
    }

    /** Whether [t] ran long enough past its opening stretch for the warm-up to be told apart. */
    fun canSplitWarmUp(t: Trip): Boolean = t.warmM > 0.0 && t.distM - t.warmM >= t.warmM

    fun split(t: Trip): EnergySplit {
        val net = t.netWh
        if (net <= 0.0) return EnergySplit(net, net, 0.0, 0.0, t.regWh, t.disWh)
        val standing = t.standWh.coerceIn(0.0, net)
        var warm = 0.0
        val rest = t.distM - t.warmM
        if (canSplitWarmUp(t)) {
            val laterRate = (net - t.warmWh) / rest
            warm = (t.warmWh - laterRate * t.warmM).coerceIn(0.0, net - standing)
        }
        return EnergySplit(net, net - standing - warm, warm, standing, t.regWh, t.disWh)
    }

    // ---------------------------------------------------------------------------------------------
    // Days and weeks
    // ---------------------------------------------------------------------------------------------

    class Rollup(val drives: Int, val distM: Double, val netWh: Double, val durationS: Double,
                 val shortTrips: Int, val regenWh: Double, val grossWh: Double) {
        val whPerKm: Double? get() = if (distM >= 100.0) netWh / (distM / 1000.0) else null
        val shortShare: Double? get() = if (drives > 0) shortTrips.toDouble() / drives else null
    }

    fun rollup(drives: List<Trip>): Rollup {
        val d = drives.filter { it.kind == Trip.Kind.DRIVE }
        return Rollup(d.size, d.sumOf { it.distM }, d.sumOf { it.netWh }, d.sumOf { it.durationS },
            d.count { it.distM < SHORT_TRIP_M }, d.sumOf { it.regWh }, d.sumOf { it.disWh })
    }

    fun onDay(trips: List<Trip>, day: Long): List<Trip> = trips.filter { dayOf(it) == day }
    fun inWeek(trips: List<Trip>, monday: Long): List<Trip> = trips.filter { dayOf(it) in monday until monday + 7 }

    /** Distance driven on each of the [days] days ending at [endDay], oldest first. */
    fun dailyDistance(drives: List<Trip>, endDay: Long, days: Int): List<Pair<Long, Double>> {
        val byDay = HashMap<Long, Double>()
        for (d in drives) if (d.kind == Trip.Kind.DRIVE) byDay.merge(dayOf(d), d.distM, Double::plus)
        return (endDay - days + 1..endDay).map { it to (byDay[it] ?: 0.0) }
    }

    class Bests(
        /** Best efficiency and highest regeneration share, each on a drive of at least [SHORT_TRIP_M]. */
        val efficiency: Trip?,
        val longest: Trip?,
        val regen: Trip?,
        val biggestDay: Pair<Long, Double>?,
    )

    fun bests(drives: List<Trip>): Bests {
        val d = drives.filter { it.kind == Trip.Kind.DRIVE && !it.open }
        val real = d.filter { it.distM >= SHORT_TRIP_M && it.netWh > 0.0 }
        val days = HashMap<Long, Double>()
        for (t in d) days.merge(dayOf(t), t.distM, Double::plus)
        return Bests(
            efficiency = real.minByOrNull { whPerKm(it) ?: Double.MAX_VALUE },
            longest = d.maxByOrNull { it.distM },
            regen = real.filter { it.disWh > 0.0 }.maxByOrNull { it.regWh / it.disWh },
            biggestDay = days.maxByOrNull { it.value }?.let { it.key to it.value },
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Charging and cost
    // ---------------------------------------------------------------------------------------------

    /** One plug-in: the recorder's records joined where they are minutes apart. */
    class ChargeSession(val t0: Long, val t1: Long, val tzMin: Int, val b0: Double, val b1: Double,
                        val powerKw: Double?, val endState: Int?, val limitPct: Double?, val capWh: Double?,
                        /** Part of it happened while the head unit slept, so its start time is the last time the car was seen. */
                        val slept: Boolean) {
        val addedWh: Double get() = b1 - b0
        /** It ran to the charge limit rather than being unplugged first. */
        val reachedLimit: Boolean get() = endState == TripRecorder.CHARGE_COMPLETE ||
            (limitPct != null && capWh != null && b1 >= 0.97 * limitPct / 100.0 * capWh)
    }

    fun sessions(trips: List<Trip>): List<ChargeSession> {
        val out = mutableListOf<ChargeSession>()
        var part = mutableListOf<Trip>()
        fun close() {
            if (part.isEmpty()) return
            val watched = part.filter { it.powerKw != null && !it.gap }
            val span = watched.sumOf { it.durationS }
            val last = part.last()
            out += ChargeSession(
                t0 = (part.firstOrNull { !it.gap } ?: part.first()).t0, t1 = last.t1, tzMin = last.tzMin,
                b0 = part.first().b0, b1 = last.b1,
                powerKw = if (span > 0.0) watched.sumOf { it.powerKw!! * it.durationS } / span else null,
                endState = if (part.any { it.endState == TripRecorder.CHARGE_COMPLETE }) TripRecorder.CHARGE_COMPLETE else last.endState,
                limitPct = last.limitPct, capWh = last.capWh,
                slept = part.any { it.gap },
            )
            part = mutableListOf()
        }
        for (c in trips.filter { it.kind == Trip.Kind.CHARGE }.sortedBy { it.t0 }) {
            if (part.isNotEmpty() && c.t0 - part.last().t1 > CHARGE_JOIN_MS) close()
            part += c
        }
        close()
        return out
    }

    class Month(val year: Int, val month: Int, val addedWh: Double, val sessions: Int, val distM: Double, val netWh: Double)

    /** Charging and driving per calendar month, newest first. */
    fun months(sessions: List<ChargeSession>, drives: List<Trip>): List<Month> {
        class Acc { var added = 0.0; var n = 0; var dist = 0.0; var net = 0.0 }
        val acc = sortedMapOf<Int, Acc>(compareByDescending { it })
        fun slot(day: Long): Acc {
            val d = LocalDate.ofEpochDay(day)
            return acc.getOrPut(d.year * 12 + d.monthValue - 1) { Acc() }
        }
        for (s in sessions) slot(epochDay(s.t1, s.tzMin)).let { it.added += s.addedWh; it.n++ }
        for (d in drives) if (d.kind == Trip.Kind.DRIVE) slot(dayOf(d)).let { it.dist += d.distM; it.net += d.netWh }
        return acc.map { (k, a) -> Month(k / 12, k % 12 + 1, a.added, a.n, a.dist, a.net) }
    }

    /** What the wall delivered for [batteryWh] in the pack, in kWh. */
    fun wallKwh(batteryWh: Double): Double = batteryWh / 1000.0 / CHARGER_EFFICIENCY
    fun cost(batteryWh: Double, perKwh: Double): Double = wallKwh(batteryWh) * perKwh
    fun milesPerKwh(whPerKm: Double): Double = 1000.0 / whPerKm * 1000.0 / M_PER_MILE
    /** Miles per gallon-equivalent, counting charger loss the way a window sticker does. */
    fun mpge(whPerKm: Double): Double = milesPerKwh(whPerKm) * CHARGER_EFFICIENCY * KWH_PER_GALLON
    fun gasCost(distM: Double, pricePerGallon: Double, mpg: Double): Double = distM / M_PER_MILE / max(mpg, 1.0) * pricePerGallon
}
