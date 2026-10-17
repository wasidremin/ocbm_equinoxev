package wasidremin.gmccpa

import kotlin.math.roundToInt

/** Presentation-only geometry. Insets are dp, not EqTheme-scaled settings UI units. */
object DisplayGeometry {
    data class Box(val x: Int, val y: Int, val width: Int, val height: Int)

    fun content(width: Int, height: Int, density: Float, c: DisplayPrefs.Controls): Box {
        val l = (c.left * density).roundToInt().coerceIn(0, (width - 1).coerceAtLeast(0))
        val r = (c.right * density).roundToInt().coerceIn(0, (width - l - 1).coerceAtLeast(0))
        val t = (c.top * density).roundToInt().coerceIn(0, (height - 1).coerceAtLeast(0))
        val b = (c.bottom * density).roundToInt().coerceIn(0, (height - t - 1).coerceAtLeast(0))
        return Box(l, t, (width - l - r).coerceAtLeast(1), (height - t - b).coerceAtLeast(1))
    }

    fun fit(area: Box, frameWidth: Int, frameHeight: Int): Box {
        if (frameWidth <= 0 || frameHeight <= 0) return area
        val ratio = minOf(area.width.toDouble() / frameWidth, area.height.toDouble() / frameHeight)
        val w = (frameWidth * ratio).roundToInt().coerceIn(1, area.width)
        val h = (frameHeight * ratio).roundToInt().coerceIn(1, area.height)
        return Box(area.x + (area.width - w) / 2, area.y + (area.height - h) / 2, w, h)
    }

    /**
     * Place the latched subscribe-time block (rail + video + strips) inside the live window.
     *
     * Pure and unit-tested; [CarPlayActivity.measureAndLayout] is the only caller.
     *
     * - A 1px difference is even-alignment rounding, not a mismatch: scale 1, origin 0.
     * - A window at least as large as the latch centers the block 1:1 (today's behaviour).
     * - A smaller window downscales the whole block to fit instead of overflowing it
     *   off-screen (2026-10-17: root 2629x967 vs latched 2914x1134 clipped 85px on the
     *   right because the negative offset was clamped to 0). The buffer stays advertised;
     *   only the on-screen rect shrinks, so touch (normalised to the view) stays correct.
     */
    data class LatchedFit(val scale: Float, val x: Int, val y: Int, val matched: Boolean)

    fun fitLatched(rootW: Int, rootH: Int, latchedW: Int, latchedH: Int): LatchedFit {
        if (latchedW <= 0 || latchedH <= 0 || rootW <= 0 || rootH <= 0)
            return LatchedFit(1f, 0, 0, true)
        val dw = rootW - latchedW
        val dh = rootH - latchedH
        if (kotlin.math.abs(dw) <= 1 && kotlin.math.abs(dh) <= 1)
            return LatchedFit(1f, 0, 0, true)
        if (rootW >= latchedW && rootH >= latchedH)
            return LatchedFit(1f, dw / 2, dh / 2, false)
        val s = minOf(rootW.toFloat() / latchedW, rootH.toFloat() / latchedH).coerceIn(0.1f, 1f)
        val x = ((rootW - latchedW * s) / 2).roundToInt().coerceAtLeast(0)
        val y = ((rootH - latchedH * s) / 2).roundToInt().coerceAtLeast(0)
        return LatchedFit(s, x, y, false)
    }
}
