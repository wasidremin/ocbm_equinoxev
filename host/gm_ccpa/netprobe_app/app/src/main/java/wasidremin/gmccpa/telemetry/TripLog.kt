package wasidremin.gmccpa.telemetry

import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * The car's own permanent record: one line per finished drive and per finished charge.
 *
 * The probe keeps only the newest 48 one-minute segments on the car, so nothing on the head unit
 * could answer "how did today compare" before this. [TripRecorder] is fed the same property and GPS
 * callbacks the probe already receives and boils each drive or charge down to one [Trip];
 * [TripStore] appends it to `insights/trips.ndjson`, outside the pruned `telemetry` directory. A
 * year of driving is under 1 MB. Nothing here is uploaded: the file holds each drive's start and
 * end coordinates (so repeated routes can be found) and never leaves the car.
 *
 * Pure Kotlin and `java.io` only, so the whole recorder runs in JVM tests.
 */
data class Trip(
    val kind: Kind,
    /** Epoch milliseconds: a drive runs from the first to the last moving sample. */
    val t0: Long,
    val t1: Long,
    /** The car's UTC offset in minutes when the record was written, for local days and clock times. */
    val tzMin: Int,
    /** Battery energy in Wh at [t0] and [t1]. */
    val b0: Double,
    val b1: Double,
    val lat0: Double? = null,
    val lon0: Double? = null,
    val lat1: Double? = null,
    val lon1: Double? = null,
    // ---- drives ----
    val distM: Double = 0.0,
    /** Energy the battery gave up and took back while driving, summed over its 10 Wh steps. */
    val disWh: Double = 0.0,
    val regWh: Double = 0.0,
    val tempC: Double? = null,
    val range0M: Double? = null,
    val range1M: Double? = null,
    val movingS: Double = 0.0,
    /** Net energy used while the car stood still inside the drive. */
    val standWh: Double = 0.0,
    /** Distance and net energy of the drive's opening stretch ([TripRecorder.WARM_M]); 0 when the drive was shorter. */
    val warmM: Double = 0.0,
    val warmWh: Double = 0.0,
    val vmaxMps: Double = 0.0,
    val upM: Double? = null,
    val downM: Double? = null,
    // ---- charges ----
    /** Average power into the battery over the stretch the head unit watched, from the level's change. */
    val powerKw: Double? = null,
    val limitPct: Double? = null,
    val capWh: Double? = null,
    /** EV_CHARGE_STATE the charge ended in: 2 when the car reported it complete, i.e. it reached the limit. */
    val endState: Int? = null,
    /** The head unit slept through it: only the level before and after is known. */
    val gap: Boolean = false,
    /** A drive the recorder still holds open; shown, never stored. */
    val open: Boolean = false,
) {
    enum class Kind { DRIVE, CHARGE }

    val netWh: Double get() = b0 - b1
    val addedWh: Double get() = b1 - b0
    val durationS: Double get() = (t1 - t0) / 1000.0

    fun toJson(): JSONObject {
        val o = JSONObject().put("k", if (kind == Kind.DRIVE) "d" else "c")
            .put("t0", t0).put("t1", t1).put("tz", tzMin).put("b0", r(b0)).put("b1", r(b1))
        fun opt(key: String, v: Double?, digits: Int = 1) { if (v != null && v.isFinite()) o.put(key, r(v, digits)) }
        opt("la0", lat0, 6); opt("lo0", lon0, 6); opt("la1", lat1, 6); opt("lo1", lon1, 6)
        if (kind == Kind.DRIVE) {
            o.put("m", r(distM)).put("dis", r(disWh)).put("reg", r(regWh)).put("mv", r(movingS))
                .put("sw", r(standWh)).put("wm", r(warmM)).put("ww", r(warmWh)).put("vx", r(vmaxMps, 2))
            opt("tc", tempC, 2); opt("r0", range0M, 0); opt("r1", range1M, 0); opt("up", upM); opt("dn", downM)
        } else {
            opt("kw", powerKw, 2); opt("lim", limitPct); opt("cap", capWh, 0)
            if (endState != null) o.put("cs", endState)
            if (gap) o.put("gap", true)
        }
        return o
    }

    companion object {
        private fun r(v: Double, digits: Int = 1): Double {
            var k = 1.0
            repeat(digits) { k *= 10.0 }
            return Math.round(v * k) / k
        }

        fun fromJson(o: JSONObject): Trip? {
            val kind = when (o.optString("k")) { "d" -> Kind.DRIVE; "c" -> Kind.CHARGE; else -> return null }
            if (!o.has("t0") || !o.has("t1") || !o.has("b0") || !o.has("b1")) return null
            fun d(key: String): Double? = if (o.has(key)) o.optDouble(key).takeIf { it.isFinite() } else null
            return Trip(
                kind = kind, t0 = o.getLong("t0"), t1 = o.getLong("t1"), tzMin = o.optInt("tz", 0),
                b0 = o.getDouble("b0"), b1 = o.getDouble("b1"),
                lat0 = d("la0"), lon0 = d("lo0"), lat1 = d("la1"), lon1 = d("lo1"),
                distM = d("m") ?: 0.0, disWh = d("dis") ?: 0.0, regWh = d("reg") ?: 0.0, tempC = d("tc"),
                range0M = d("r0"), range1M = d("r1"), movingS = d("mv") ?: 0.0, standWh = d("sw") ?: 0.0,
                warmM = d("wm") ?: 0.0, warmWh = d("ww") ?: 0.0, vmaxMps = d("vx") ?: 0.0,
                upM = d("up"), downM = d("dn"),
                powerKw = d("kw"), limitPct = d("lim"), capWh = d("cap"),
                endState = if (o.has("cs")) o.optInt("cs") else null, gap = o.optBoolean("gap", false),
            )
        }
    }
}

/**
 * Turns the live callback stream into [Trip]s. Not thread safe: the probe's worker thread owns it.
 *
 * A drive opens at the first moving speed sample and closes once the car has not moved for
 * [IDLE_CLOSE_MS]; it ends at the last moving sample, so the minutes parked afterwards are not
 * counted. A charge is the battery level rising while the car stands and reports itself charging.
 * Power is taken from the level's change over time because the car's own charge-rate property
 * always reads 0.
 *
 * The head unit is often killed minutes after parking, before a drive has timed out, so the open
 * state is checkpointed ([checkpoint] / [restore]) and the drive is finished on the next start.
 */
class TripRecorder(
    private val tzMin: (Long) -> Int = { 0 },
    private val emit: (Trip) -> Unit,
) {
    private class Acc(
        var dist: Double = 0.0, var moving: Double = 0.0, var dis: Double = 0.0, var reg: Double = 0.0,
        var standWh: Double = 0.0, var b1: Double = Double.NaN, var range1: Double? = null,
        var tempSum: Double = 0.0, var tempN: Int = 0, var vmax: Double = 0.0,
        var lat1: Double? = null, var lon1: Double? = null, var up: Double = 0.0, var down: Double = 0.0,
        var alt: Boolean = false, var warmM: Double = 0.0, var warmWh: Double = 0.0,
    ) {
        fun copy() = Acc(dist, moving, dis, reg, standWh, b1, range1, tempSum, tempN, vmax, lat1, lon1, up, down, alt, warmM, warmWh)
        fun toJson(): JSONObject = JSONObject().put("dist", dist).put("moving", moving).put("dis", dis).put("reg", reg)
            .put("standWh", standWh).putOpt("b1", b1.takeIf { it.isFinite() }).putOpt("range1", range1)
            .put("tempSum", tempSum).put("tempN", tempN).put("vmax", vmax).putOpt("lat1", lat1).putOpt("lon1", lon1)
            .put("up", up).put("down", down).put("alt", alt).put("warmM", warmM).put("warmWh", warmWh)
        companion object {
            fun from(o: JSONObject) = Acc(
                o.optDouble("dist", 0.0), o.optDouble("moving", 0.0), o.optDouble("dis", 0.0), o.optDouble("reg", 0.0),
                o.optDouble("standWh", 0.0), o.optDouble("b1", Double.NaN), num(o, "range1"),
                o.optDouble("tempSum", 0.0), o.optInt("tempN", 0), o.optDouble("vmax", 0.0), num(o, "lat1"), num(o, "lon1"),
                o.optDouble("up", 0.0), o.optDouble("down", 0.0), o.optBoolean("alt", false),
                o.optDouble("warmM", 0.0), o.optDouble("warmWh", 0.0),
            )
        }
    }

    private class Drive(val t0: Long) {
        var last = t0
        var b0 = Double.NaN
        var bPrev = Double.NaN
        var range0: Double? = null
        var lat0: Double? = null
        var lon0: Double? = null
        var pT = 0L
        var pV = 0.0
        var altRef = Double.NaN
        var live = Acc()
        var done: Acc? = null
        /** The battery every [RollingEfficiency.STEP_M] of this drive, for the Drive card. */
        val marks = ArrayDeque<RollingEfficiency.Mark>()
    }

    private class Charge(val t0: Long, val seenB0: Double) {
        /** Start level; earlier than [seenB0] when the charge began before the head unit woke. */
        var b0 = seenB0
        var t1 = t0
        var b1 = seenB0
        var lat: Double? = null
        var lon: Double? = null
        /** The car said it was charging at some point, which is what tells a charge from a resting battery's drift. */
        var charging = false
        var endState: Int? = null
    }

    /** A rise across a sleep too small to be believed on its own: the start of a charge if one follows. */
    private class Rise(val t: Long, val b0: Double)

    private var bT = 0L
    private var b = Double.NaN
    private var speed = 0.0
    private var rangeM: Double? = null
    private var tempC: Double? = null
    private var limitPct: Double? = null
    private var capWh: Double? = null
    private var chargeState: Int? = null
    private var distUnits: Int? = null
    private var fixT = 0L
    private var fixLat = 0.0
    private var fixLon = 0.0
    private var drive: Drive? = null
    private var charge: Charge? = null
    private var rise: Rise? = null
    /** The marks the drives before this one left ([RollingEfficiency.appended]): the start of the next drive's line. */
    private var tail: List<RollingEfficiency.Mark> = emptyList()
    private val power = LivePower()

    /** A property callback with status 0. Ids the recorder does not use are ignored. */
    fun onProperty(t: Long, id: Int, value: Any?) {
        val n = (value as? Number)?.toDouble() ?: return
        if (!n.isFinite()) return
        when (id) {
            InsightsData.SPEED_MPS -> onSpeed(t, n)
            InsightsData.BATTERY_WH -> onBattery(t, n)
            InsightsData.RANGE_M -> { rangeM = n; drive?.let { if (it.range0 == null) it.range0 = n; it.live.range1 = n } }
            InsightsData.OUTSIDE_C -> { tempC = n; drive?.live?.let { it.tempSum += n; it.tempN++ } }
            InsightsData.CHARGE_LIMIT_PCT -> limitPct = n
            InsightsData.CAPACITY_WH -> if (n > 1000.0) capWh = n
            InsightsData.DISTANCE_UNITS -> distUnits = n.toInt()
            InsightsData.CHARGE_STATE -> {
                chargeState = n.toInt()
                charge?.let {
                    if (n.toInt() == CHARGING) it.charging = true
                    else if (it.endState != CHARGE_COMPLETE) it.endState = n.toInt()
                }
            }
        }
    }

    fun onSpeed(t: Long, v: Double) {
        tick(t)
        val moving = abs(v) > MOVING_MPS
        var d = drive
        if (d == null && moving) {
            closeCharge()
            d = Drive(t).also { n ->
                n.b0 = b
                n.range0 = rangeM
                tempC?.let { n.live.tempSum = it; n.live.tempN = 1 }
                n.live.range1 = rangeM
                // The car stood still before it moved, so a recent fix is the place it left from.
                if (fixT != 0L && t - fixT in 0..FIX_FRESH_MS) { n.lat0 = fixLat; n.lon0 = fixLon; n.live.lat1 = fixLat; n.live.lon1 = fixLon }
            }
            drive = d
        }
        if (d != null) {
            val dt = (t - d.pT) / 1000.0
            if (d.pT != 0L && dt > 0.0 && dt < MAX_STEP_S) {
                d.live.dist += (abs(d.pV) + abs(v)) / 2.0 * dt
                if (abs(d.pV) > MOVING_MPS) d.live.moving += dt
            }
            d.pT = t
            d.pV = v
            d.live.vmax = max(d.live.vmax, abs(v))
            power.onSpeed(t, v, tempC)
            if (b.isFinite()) RollingEfficiency.add(d.marks, RollingEfficiency.Mark(d.live.dist, b))
            if (d.live.warmM == 0.0 && d.live.dist >= WARM_M && d.b0.isFinite() && b.isFinite()) {
                d.live.warmM = d.live.dist
                d.live.warmWh = d.b0 - b
            }
            if (moving) {
                d.last = t
                d.live.b1 = b
                d.done = d.live.copy()
            }
        }
        speed = v
    }

    fun onBattery(t: Long, wh: Double) {
        tick(t)
        val prevT = bT
        val prev = b
        bT = t
        b = wh
        power.onBattery(wh)
        val d = drive
        if (d != null) {
            if (!d.b0.isFinite()) d.b0 = wh
            if (d.bPrev.isFinite()) {
                val delta = wh - d.bPrev
                if (delta < 0) d.live.dis -= delta else d.live.reg += delta
                if (abs(speed) <= MOVING_MPS) d.live.standWh -= delta
            }
            d.bPrev = wh
        }
        if (!prev.isFinite()) return
        val dt = t - prevT
        val parked = abs(speed) <= MOVING_MPS && (d == null || t - d.last > DRIVE_QUIET_MS)
        when {
            dt > BATTERY_GAP_MS -> {
                closeCharge()
                rise = null
                val added = wh - prev
                if (added < MIN_CHARGE_WH || d != null) return
                // Charged while the head unit slept: the level before and after is all there is. A
                // resting battery also drifts up by a few hundred Wh, so a small rise counts only
                // if the car says it is charging, or as the head of a charge that starts right after.
                if (added >= SURE_CHARGE_WH || chargeState == CHARGING || chargeState == CHARGE_COMPLETE) {
                    emit(Trip(Trip.Kind.CHARGE, prevT, t, tzMin(t), prev, wh, lat0 = freshFix(t)?.first, lon0 = freshFix(t)?.second,
                        limitPct = limitPct, capWh = capWh, endState = chargeState, gap = true))
                } else {
                    rise = Rise(t, prev)
                }
            }
            wh > prev && parked -> {
                val c = charge ?: Charge(prevT, prev).also { n ->
                    freshFix(t)?.let { n.lat = it.first; n.lon = it.second }
                    n.charging = chargeState == CHARGING
                    rise?.let { r -> if (prevT - r.t <= RISE_JOIN_MS) n.b0 = r.b0 }
                    rise = null
                    charge = n
                }
                c.t1 = t
                c.b1 = wh
            }
        }
    }

    /** A GPS fix. Altitude is optional; fixes worse than [MAX_FIX_ACCURACY_M] are dropped. */
    fun onLocation(t: Long, lat: Double, lon: Double, accuracyM: Double?, altitudeM: Double?) {
        tick(t)
        if (accuracyM != null && accuracyM > MAX_FIX_ACCURACY_M) return
        fixT = t
        fixLat = lat
        fixLon = lon
        val d = drive ?: return
        if (d.lat0 == null && d.live.dist < START_FIX_WITHIN_M) { d.lat0 = lat; d.lon0 = lon }
        d.live.lat1 = lat
        d.live.lon1 = lon
        if (altitudeM != null && altitudeM.isFinite()) {
            // GPS height wanders by metres; only a move of ALT_STEP_M from the last anchor counts.
            if (!d.altRef.isFinite()) d.altRef = altitudeM
            val step = altitudeM - d.altRef
            if (abs(step) >= ALT_STEP_M) {
                if (step > 0) d.live.up += step else d.live.down -= step
                d.altRef = altitudeM
                d.live.alt = true
            }
        }
    }

    /** Close whatever has gone quiet. Every callback calls it; the probe also calls it once a minute. */
    fun tick(t: Long) {
        drive?.let { if (t - it.last > IDLE_CLOSE_MS || t < it.last - IDLE_CLOSE_MS) closeDrive() }
        charge?.let { if (t - it.t1 > CHARGE_QUIET_MS) closeCharge() }
    }

    private fun freshFix(t: Long): Pair<Double, Double>? =
        if (fixT != 0L && t - fixT in 0..FIX_FRESH_MS) fixLat to fixLon else null

    private fun driveTrip(d: Drive, open: Boolean): Trip? {
        val a = d.done ?: return null
        if (a.dist < MIN_DRIVE_M || !d.b0.isFinite() || !a.b1.isFinite()) return null
        return Trip(
            Trip.Kind.DRIVE, d.t0, d.last, tzMin(d.last), d.b0, a.b1,
            lat0 = d.lat0, lon0 = d.lon0, lat1 = a.lat1, lon1 = a.lon1,
            distM = a.dist, disWh = a.dis, regWh = a.reg, tempC = if (a.tempN > 0) a.tempSum / a.tempN else null,
            range0M = d.range0, range1M = a.range1, movingS = a.moving, standWh = a.standWh,
            warmM = a.warmM, warmWh = a.warmWh, vmaxMps = a.vmax,
            upM = a.up.takeIf { a.alt }, downM = a.down.takeIf { a.alt }, open = open,
        )
    }

    private fun closeDrive() {
        val d = drive ?: return
        drive = null
        power.reset()
        val trip = driveTrip(d, open = false) ?: return
        tail = RollingEfficiency.appended(tail, d.marks)
        emit(trip)
    }

    private fun closeCharge() {
        val c = charge ?: return
        charge = null
        val added = c.b1 - c.b0
        // Where the car reports a charge state, a rise it never called charging has to be large to count.
        val believed = c.charging || chargeState == null || added >= SURE_CHARGE_WH
        if (added < MIN_CHARGE_WH || !believed) return
        val hours = (c.t1 - c.t0) / 3_600_000.0
        emit(Trip(Trip.Kind.CHARGE, c.t0, c.t1, tzMin(c.t1), c.b0, c.b1, lat0 = c.lat, lon0 = c.lon,
            powerKw = if (c.t1 - c.t0 >= MIN_POWER_SPAN_MS) (c.b1 - c.seenB0) / hours / 1000.0 else null,
            limitPct = limitPct, capWh = capWh, endState = c.endState ?: chargeState))
    }

    /** The drive in progress as it would be recorded if it ended now, or null. */
    fun openDrive(): Trip? = drive?.let { driveTrip(it, open = true) }

    /** What the car reads right now, with the drive in progress, for [DriveLive]. */
    fun live(t: Long): DriveLive.State = DriveLive.State(openDrive(), b.takeIf { it.isFinite() }, capWh, tempC,
        speedMps = abs(speed), metric = distUnits == KILOMETERS, atMs = t, marks = drive?.marks?.toList() ?: emptyList(),
        tail = tail, powerW = power.watts?.takeIf { drive != null && t - power.atMs in 0..LIVE_POWER_FRESH_MS })

    /** Everything needed to pick up after the process is killed, as one small JSON document. */
    fun checkpoint(): String {
        val o = JSONObject().put("v", 1).put("bT", bT).putOpt("b", b.takeIf { it.isFinite() }).put("speed", speed)
            .putOpt("range", rangeM).putOpt("temp", tempC).putOpt("limit", limitPct).putOpt("cap", capWh)
            .putOpt("cs", chargeState).put("fixT", fixT).put("fixLat", fixLat).put("fixLon", fixLon)
        drive?.let { d ->
            o.put("drive", JSONObject().put("t0", d.t0).put("last", d.last).putOpt("b0", d.b0.takeIf { it.isFinite() })
                .putOpt("bPrev", d.bPrev.takeIf { it.isFinite() }).putOpt("range0", d.range0)
                .putOpt("lat0", d.lat0).putOpt("lon0", d.lon0).put("pT", d.pT).put("pV", d.pV)
                .putOpt("altRef", d.altRef.takeIf { it.isFinite() }).put("live", d.live.toJson())
                .putOpt("done", d.done?.toJson()).put("marks", RollingEfficiency.pack(d.marks)))
            driveTrip(d, open = true)?.let { o.put("trip", it.toJson()) }
        }
        charge?.let { c ->
            o.put("charge", JSONObject().put("t0", c.t0).put("b0", c.b0).put("sb0", c.seenB0).put("t1", c.t1).put("b1", c.b1)
                .put("chg", c.charging).putOpt("lat", c.lat).putOpt("lon", c.lon).putOpt("es", c.endState))
        }
        rise?.let { o.put("rise", JSONObject().put("t", it.t).put("b0", it.b0)) }
        if (tail.isNotEmpty()) o.put("tail", RollingEfficiency.pack(tail))
        return o.toString()
    }

    /** Load a [checkpoint] at [now]. Anything that went quiet while the process was gone is closed at once. */
    fun restore(json: String?, now: Long) {
        if (json.isNullOrBlank()) return
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        if (o.optInt("v") != 1) return
        bT = o.optLong("bT", 0L)
        b = o.optDouble("b", Double.NaN)
        speed = 0.0
        rangeM = num(o, "range"); tempC = num(o, "temp"); limitPct = num(o, "limit"); capWh = num(o, "cap")
        chargeState = if (o.has("cs")) o.optInt("cs") else null
        fixT = o.optLong("fixT", 0L); fixLat = o.optDouble("fixLat", 0.0); fixLon = o.optDouble("fixLon", 0.0)
        o.optJSONObject("drive")?.let { j ->
            drive = Drive(j.optLong("t0")).also { d ->
                d.last = j.optLong("last", d.t0)
                d.b0 = j.optDouble("b0", Double.NaN); d.bPrev = j.optDouble("bPrev", Double.NaN)
                d.range0 = num(j, "range0"); d.lat0 = num(j, "lat0"); d.lon0 = num(j, "lon0")
                // The gap while the process was gone is not driven distance: start the integral afresh.
                d.pT = 0L; d.pV = 0.0
                d.altRef = j.optDouble("altRef", Double.NaN)
                d.live = j.optJSONObject("live")?.let { Acc.from(it) } ?: Acc()
                d.done = j.optJSONObject("done")?.let { Acc.from(it) }
                d.marks.addAll(RollingEfficiency.unpack(j.optString("marks").ifEmpty { null }))
            }
        }
        o.optJSONObject("charge")?.let { j ->
            charge = Charge(j.optLong("t0"), j.optDouble("sb0", j.optDouble("b0"))).also { c ->
                c.b0 = j.optDouble("b0", c.seenB0)
                c.t1 = j.optLong("t1", c.t0); c.b1 = j.optDouble("b1", c.seenB0)
                c.charging = j.optBoolean("chg", false); c.lat = num(j, "lat"); c.lon = num(j, "lon")
                c.endState = if (j.has("es")) j.optInt("es") else null
            }
        }
        o.optJSONObject("rise")?.let { j -> rise = Rise(j.optLong("t"), j.optDouble("b0")) }
        tail = RollingEfficiency.unpack(o.optString("tail").ifEmpty { null })
        tick(now)
    }

    companion object {
        const val MOVING_MPS = 0.3
        /** [LivePower]'s figure is shown only while the speed readings behind it are this recent. */
        const val LIVE_POWER_FRESH_MS = 4_000L
        /** A drive is over once the car has stood this long; shorter stops stay inside the drive. */
        const val IDLE_CLOSE_MS = 240_000L
        const val MIN_DRIVE_M = 500.0
        /** Speed samples further apart than this are a hole in the data, not distance. */
        const val MAX_STEP_S = 10.0
        /** The opening stretch reported apart from the rest: the cabin and battery are warming up. */
        const val WARM_M = 3000.0
        const val MIN_CHARGE_WH = 300.0
        const val BATTERY_GAP_MS = 300_000L
        const val CHARGE_QUIET_MS = 600_000L
        /** The level rising right after a stop is regeneration settling, not a charge. */
        const val DRIVE_QUIET_MS = 60_000L
        /** A rise this large is a charge whatever the charge state said. */
        const val SURE_CHARGE_WH = 2000.0
        const val RISE_JOIN_MS = 900_000L
        /** Power over a shorter stretch than this is two 10 Wh steps and a guess. */
        const val MIN_POWER_SPAN_MS = 300_000L
        const val MAX_FIX_ACCURACY_M = 50.0
        const val FIX_FRESH_MS = 1_800_000L
        const val START_FIX_WITHIN_M = 300.0
        const val ALT_STEP_M = 5.0
        /** EV_CHARGE_STATE values. */
        const val CHARGING = 1
        const val CHARGE_COMPLETE = 2
        /** DISTANCE_DISPLAY_UNITS: VehicleUnit.KILOMETER. Anything else is shown in miles. */
        const val KILOMETERS = 35

        private fun num(o: JSONObject, key: String): Double? =
            if (o.has(key) && !o.isNull(key)) o.optDouble(key).takeIf { it.isFinite() } else null

        /** The open drive stored in a [checkpoint], for a page that wants it before it is closed. */
        fun openDriveOf(checkpoint: String?): Trip? {
            if (checkpoint.isNullOrBlank()) return null
            val trip = runCatching { JSONObject(checkpoint).optJSONObject("trip") }.getOrNull() ?: return null
            return Trip.fromJson(trip)?.copy(open = true)
        }
    }
}

/**
 * The recorder's latest reading, published from the probe's worker thread about once a second for
 * the driver display's Drive card, which runs on the main thread. Null while nothing records.
 */
object DriveLive {
    class State(
        /** The drive in progress; null until it has gone [TripRecorder.MIN_DRIVE_M]. */
        val drive: Trip?,
        val batteryWh: Double?,
        val capWh: Double?,
        val tempC: Double?,
        val speedMps: Double,
        val metric: Boolean,
        /** Epoch milliseconds of the reading. */
        val atMs: Long,
        /** The drive under way's battery marks ([RollingEfficiency]); empty with no drive. */
        val marks: List<RollingEfficiency.Mark> = emptyList(),
        /** The marks the drives before left, joined end to end: what the line shows before this drive. */
        val tail: List<RollingEfficiency.Mark> = emptyList(),
        /** The power in use now, in watts, negative regenerating ([LivePower]); null when unknown. */
        val powerW: Double? = null,
    )

    /** A reading older than this is the past: on-change properties go quiet at a long stop, not for this long. */
    const val STALE_MS = 6 * 60_000L

    @Volatile var state: State? = null

    fun fresh(nowMs: Long): State? = state?.takeIf { nowMs - it.atMs in 0..STALE_MS }
}

/** The permanent file and the recorder's checkpoint, both under one directory. */
class TripStore(private val dir: File) {
    private val file = File(dir, "trips.ndjson")
    private val openFile = File(dir, "open.json")

    fun append(trip: Trip) {
        runCatching {
            dir.mkdirs()
            synchronized(LOCK) { file.appendText(trip.toJson().toString() + "\n", Charsets.UTF_8) }
        }
    }

    /** Every stored record in time order. A torn last line (power lost mid-write) is skipped. */
    fun load(): List<Trip> {
        if (!file.isFile) return emptyList()
        val lines = synchronized(LOCK) { runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList()) }
        return lines.mapNotNull { line ->
            if (line.isBlank()) null else runCatching { Trip.fromJson(JSONObject(line)) }.getOrNull()
        }.sortedBy { it.t0 }
    }

    fun saveCheckpoint(json: String) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "open.json.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (!tmp.renameTo(openFile)) { openFile.delete(); tmp.renameTo(openFile) }
        }
    }

    fun loadCheckpoint(): String? = runCatching { openFile.takeIf { it.isFile }?.readText(Charsets.UTF_8) }.getOrNull()

    /**
     * Stored records plus the drive the recorder still holds open, which is the one the driver just
     * finished: it is written out only once the car has stood for [TripRecorder.IDLE_CLOSE_MS], or
     * on the next start when the head unit was shut down first.
     */
    fun loadWithOpen(): List<Trip> {
        val stored = load()
        val open = TripRecorder.openDriveOf(loadCheckpoint()) ?: return stored
        if (stored.any { it.kind == Trip.Kind.DRIVE && it.t0 == open.t0 }) return stored
        return (stored + open).sortedBy { it.t0 }
    }

    fun bytes(): Long = if (file.isFile) file.length() else 0L

    fun clear() {
        synchronized(LOCK) { file.delete() }
        openFile.delete()
    }

    companion object {
        private val LOCK = Any()

        fun dirUnder(filesDir: File): File = File(filesDir, "insights")
    }
}
