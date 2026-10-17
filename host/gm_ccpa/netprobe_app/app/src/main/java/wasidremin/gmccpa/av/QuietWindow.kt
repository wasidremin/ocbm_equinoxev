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
