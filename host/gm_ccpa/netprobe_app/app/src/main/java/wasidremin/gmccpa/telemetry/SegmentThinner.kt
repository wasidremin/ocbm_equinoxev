package wasidremin.gmccpa.telemetry

import kotlin.math.abs

/**
 * Decides which one-minute telemetry segments are worth the car's mobile data.
 *
 * Measured on the car (November 2026): 71% of the uploaded minutes were stationary - the head unit
 * is awake for about five hours a day and the car moves in under a third of them - and every one of
 * those minutes cost as much data as a driven one. So:
 *
 *  - a minute in which the car moved is always sent;
 *  - so are the [settleMs] after the last movement, which keeps a drive's stops and its end in one
 *    unbroken run on the server;
 *  - a parked minute is sent at most every [chargingEveryMs] while the pack's energy is changing
 *    (charging, or preconditioning draining it) and otherwise every [idleEveryMs] as a heartbeat.
 *
 * Replayed over that month's 2,977 segments this keeps 1,367 of them. It governs UPLOADS only: the
 * on-car drive log is fed from the live callbacks and never loses a sample to this.
 *
 * Pure and clock-free (the caller passes a monotonic time) so the rule is unit-tested.
 */
class SegmentThinner(
    private val settleMs: Long = SETTLE_MS,
    private val chargingEveryMs: Long = CHARGING_EVERY_MS,
    private val idleEveryMs: Long = IDLE_EVERY_MS,
    private val energyStepWh: Double = ENERGY_STEP_WH,
) {
    enum class Decision(val keep: Boolean) {
        MOVING(true),
        SETTLING(true),
        ENERGY_CHANGING(true),
        HEARTBEAT(true),
        PARKED_SKIP(false),
    }

    private var lastMovedMs: Long? = null
    private var lastKeptMs: Long? = null
    private var keptBatteryWh: Double? = null

    /**
     * @param nowMs monotonic time the segment closed
     * @param moved the vehicle speed left zero at some point in the segment
     * @param batteryWh the last pack energy seen, or null when the car has not reported one
     */
    fun decide(nowMs: Long, moved: Boolean, batteryWh: Double?): Decision {
        if (moved) lastMovedMs = nowMs
        val sinceMoved = lastMovedMs?.let { nowMs - it }
        val sinceKept = lastKeptMs?.let { nowMs - it }
        val kept = keptBatteryWh
        val energyChanging = batteryWh != null && kept != null && abs(batteryWh - kept) >= energyStepWh
        val decision = when {
            moved -> Decision.MOVING
            sinceMoved != null && sinceMoved <= settleMs -> Decision.SETTLING
            energyChanging && (sinceKept == null || sinceKept >= chargingEveryMs) -> Decision.ENERGY_CHANGING
            sinceKept == null || sinceKept >= idleEveryMs -> Decision.HEARTBEAT
            else -> Decision.PARKED_SKIP
        }
        if (decision.keep) {
            lastKeptMs = nowMs
            if (batteryWh != null) keptBatteryWh = batteryWh
        }
        return decision
    }

    companion object {
        const val SETTLE_MS = 4 * 60_000L
        const val CHARGING_EVERY_MS = 5 * 60_000L
        const val IDLE_EVERY_MS = 15 * 60_000L
        const val ENERGY_STEP_WH = 300.0
        /** Below this the speed signal is treated as standing still (sensor noise at rest), m/s. */
        const val MOVING_MPS = 0.3
    }
}
