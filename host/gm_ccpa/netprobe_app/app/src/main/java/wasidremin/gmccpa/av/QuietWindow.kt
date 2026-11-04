package wasidremin.gmccpa.av

import wasidremin.gmccpa.match.ScreenModel
import kotlin.math.abs
import kotlin.math.roundToInt

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

/**
 * Where a toggle tap has landed, read from one frame: the Dashboard, or Home with the target's grid
 * icon at ([x], [y]) in 1/4-scale pixels. See [toggleLanding].
 */
internal data class Landing(val surface: ScreenModel.Surface, val x: Int = 0, val y: Int = 0)

/**
 * The toggle's landing, or null while it has not landed. A toggle tap always lands on Home or the
 * Dashboard, and iOS 27 draws the glyph of the OTHER one (the Dashboard shows the grid, Home the
 * square), so a tap that has landed always flips the reading from [before]. On Home the target's
 * [grid] icon must also be found, since the glyph flips while the icons are still flying in. Without
 * a grid template a NOT_DASHBOARD flip is enough.
 */
internal fun toggleLanding(
    before: ScreenModel.Surface,
    now: ScreenModel.Surface,
    grid: (() -> ScreenModel.Hit)?,
    width: Int,
    height: Int,
): Landing? = when {
    now == before -> null
    now == ScreenModel.Surface.DASHBOARD -> Landing(now)
    grid != null -> grid().takeIf { it.passed }?.let {
        Landing(ScreenModel.Surface.NOT_DASHBOARD, (it.nx * width).roundToInt(), (it.ny * height).roundToInt())
    }
    now == ScreenModel.Surface.NOT_DASHBOARD -> Landing(now)
    else -> null
}

/** Two landings that agree, the grid icon within a pixel: the icon has stopped moving. */
internal fun sameLanding(a: Landing, b: Landing): Boolean =
    a.surface == b.surface && abs(a.x - b.x) <= 1 && abs(a.y - b.y) <= 1
