package wasidremin.gmccpa.telemetry

import kotlin.math.abs
import kotlin.math.exp

/**
 * The power the car is using right now, in watts, for the Drive card's live figure and power line
 * ([wasidremin.gmccpa.nav.DriveCard]). The car gives an app no such reading
 * (`EV_BATTERY_INSTANTANEOUS_CHARGE_RATE` is a constant 0 on the Equinox EV), so this is an estimate
 * from two things it does give:
 *
 * - **The speed, about once a second.** [DriveOutlook.cruise] says what holding that speed costs, and
 *   the change of speed over the last [ACCEL_S] says what the car's own motion is taking or giving
 *   back ([DriveOutlook.MASS_KG], with [DRIVE_EFF] and [DriveOutlook.REGEN]). That answers the pedal
 *   within a second and knows nothing of hills, wind or the heater.
 * - **The battery, in 10 Wh steps every second or two.** Exact, but a step is 18 kW over two seconds,
 *   so on its own it has to be averaged for many seconds before it means anything.
 *
 * The estimate is the first plus a slow correction: the difference between what the battery lost and
 * what the model said, smoothed twice over [FIX_S]. Replayed over a month of the owner's drives
 * (159,150 seconds, 2026-11-14; `host/gm_ccpa/docs/14_LESSONS_LEARNED.md` §13.6): over 30 s windows,
 * where the battery itself is good to 1.2 kW, the estimate is off by a median 1.7 kW (11% above
 * 8 kW), against 5.5 kW (37%) for the model alone and 3.1 kW (21%) for the battery smoothed as
 * steadily. The battery reading is not late: shifting it earlier or later only made the fit worse.
 *
 * Pure Kotlin, fed by [TripRecorder] on the probe's worker thread.
 */
class LivePower {
    private var lastT = 0L
    private var lastB = Double.NaN
    private var b = Double.NaN
    private val speeds = ArrayDeque<Pair<Long, Double>>()
    private var fix1 = 0.0
    private var fix2 = 0.0
    private var seenS = 0.0

    /** Watts, positive using and negative regenerating; null until [SETTLE_S] of readings. */
    var watts: Double? = null
        private set
    /** Epoch milliseconds of the speed reading [watts] is for. */
    var atMs = 0L
        private set

    fun onBattery(wh: Double) { b = wh }

    fun onSpeed(t: Long, vMps: Double, tempC: Double?) {
        val v = abs(vMps)
        val dt = (t - lastT) / 1000.0
        if (lastT == 0L || dt <= 0.0 || dt > MAX_GAP_S) {
            // The first reading, or the first after a gap: nothing to difference against yet.
            reset()
            lastT = t; lastB = b
            speeds.addLast(t to v)
            return
        }
        speeds.addLast(t to v)
        while (speeds.size > 2 && t - speeds[1].first >= ACCEL_S * 1000) speeds.removeFirst()
        val (t0, v0) = speeds.first()
        val a = if (t > t0) (v - v0) / ((t - t0) / 1000.0) else 0.0
        val model = model(v, a, tempC)
        if (b.isFinite() && lastB.isFinite()) {
            val measured = (lastB - b) * 3600.0 / dt
            val k = 1 - exp(-dt / FIX_S)
            fix1 += k * ((measured - model) - fix1)
            fix2 += k * (fix1 - fix2)
            seenS += dt
        }
        lastT = t; lastB = b
        atMs = t
        watts = if (seenS >= SETTLE_S) model + fix2 else null
    }

    fun reset() {
        speeds.clear(); fix1 = 0.0; fix2 = 0.0; seenS = 0.0; watts = null; lastT = 0L; lastB = Double.NaN
    }

    companion object {
        /** The speed's change is taken over this long: one second of it is mostly the reading's own steps. */
        const val ACCEL_S = 2.0
        /** The correction's smoothing, applied twice. */
        const val FIX_S = 4.0
        const val SETTLE_S = 12.0
        const val MAX_GAP_S = 5.0
        /** Battery to wheels while accelerating. */
        const val DRIVE_EFF = 0.9

        /** Pure: what the model alone says, in watts, at [v] m/s gaining [a] m/s². */
        fun model(v: Double, a: Double, tempC: Double?): Double {
            val steady = if (v > TripRecorder.MOVING_MPS) DriveOutlook.cruise(v, tempC) * v * 3.6 else 0.0
            val motion = DriveOutlook.MASS_KG * a * v
            return steady + if (motion > 0) motion / DRIVE_EFF else motion * DriveOutlook.REGEN
        }

        /** Pure: [watts] at [vMps] as Wh/km; null standing still, where a rate per distance means nothing. */
        fun whPerKm(watts: Double, vMps: Double): Double? =
            if (vMps < MIN_RATE_MPS) null else watts / (vMps * 3.6)

        const val MIN_RATE_MPS = 2.0
    }
}
