package wasidremin.gmccpa.av

/**
 * Pure post-tap settle rule. Settled = at least [minAfterTapMs] since the tap AND consecutive
 * samples quiet (MAD < [madThreshold]) for at least [quietMs]. Times are monotonic ms.
 */
class QuietWindow(
    private val tapAtMs: Long,
    private val minAfterTapMs: Long,
    private val quietMs: Long,
    private val madThreshold: Float,
) {
    private var quietSinceMs = -1L

    /** Feed the MAD between this sample and the previous one. Returns true once settled. */
    fun offer(nowMs: Long, madFromPrevious: Float): Boolean {
        if (madFromPrevious.isNaN() || madFromPrevious >= madThreshold) {
            quietSinceMs = -1L
            return false
        }
        if (quietSinceMs < 0) quietSinceMs = nowMs
        return nowMs - tapAtMs >= minAfterTapMs && nowMs - quietSinceMs >= quietMs
    }
}

/**
 * True when a frame that never settled should be read as it is rather than end the launch. Under
 * an Apple Maps route the map moves in every frame, so waiting for a still one aborted launches
 * whose tap had already gone through (2026-11-02: Maps and Home, `TIMEOUT_UNSTABLE`). The strip
 * and its toggle glyph, all the launcher reads, do not move with the map. An interrupt still stops.
 */
internal fun readUnsettled(reason: CarPlayFrameGrabber.FailureReason, interrupted: Boolean): Boolean =
    reason == CarPlayFrameGrabber.FailureReason.TIMEOUT_UNSTABLE && !interrupted
