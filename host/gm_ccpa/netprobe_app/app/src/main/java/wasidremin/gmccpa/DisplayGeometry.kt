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

    /**
     * Right-edge x for the custom top bar's right cluster (clock, temperature, status icons).
     *
     * The Equinox EV panel is not a rectangle: the top edge slopes down left-to-right and the
     * right side is cut away (2026-10-18 vehicle report: safe insets 0/167/285/0 on a 2914x1134
     * window). The bar itself spans the full window so its background stays edge to edge, but
     * content right-aligned at the window edge lands under the cutout — the capture proved the
     * clock + temperature (the rightmost items) were drawn at x 2700-2850, i.e. on missing glass.
     * [rightInsetPx] is the live `displayCutout.safeInsetRight`; the cluster starts that far left
     * of the end margin so temp/time stay visible. The end margin (0.55 x bar height) is a
     * deliberate indent from the side, matching the car's own bar, whose right cluster sits
     * inboard of the edge — not flush against it. Pure and unit-tested.
     */
    fun clusterRightEdge(viewWidth: Float, barHeight: Float, rightInsetPx: Int): Float =
        viewWidth - barHeight * 0.55f - rightInsetPx.coerceAtLeast(0).toFloat()

    /**
     * Right edge x of the bar's left icon block: last icon centre plus half a pitch.
     *
     * Mirrors [wasidremin.gmccpa.GmTopBarView]'s loop (first centre `firstCxFrac` x height,
     * `count` icons at `pitchFrac` x height). The right cluster must start at or right of this
     * x; [clusterFitScale] shrinks it when the natural width does not fit. Pure and unit-tested.
     */
    fun leftBlockEnd(barHeight: Float, firstCxFrac: Float = 0.95f, pitchFrac: Float = 0.88f, count: Int = 7): Float =
        barHeight * (firstCxFrac + (count - 1) * pitchFrac + pitchFrac / 2f)

    /**
     * Uniform downscale so the right cluster fits between [leftBlockEnd] and [clusterRightEdge].
     *
     * 1 when the natural width fits (the common case — the bar draws at its designed sizes);
     * a fraction that lands the cluster exactly in the available slot when it does not (narrow
     * window, large cutout inset, long clock/temperature strings). Never upscales: extra room
     * stays empty rather than inflating the cluster. Zero or negative room hides the cluster
     * (scale 0) instead of overlapping the left icons. Pure and unit-tested.
     */
    fun clusterFitScale(availableWidth: Float, naturalWidth: Float): Float {
        if (naturalWidth <= 0f) return 1f
        if (availableWidth <= 0f) return 0f
        return (availableWidth / naturalWidth).coerceIn(0f, 1f)
    }

    /**
     * Index of the segmented-control preset nearest to a persisted [value]. A saved value from a
     * retired preset list (or a hand-set exact code) maps to its closest stop instead of falling
     * back to a fixed default that silently changes the behaviour. Returns 0 for an empty list.
     */
    fun nearestPresetIndex(presets: IntArray, value: Int): Int {
        if (presets.isEmpty()) return 0
        var best = 0
        var bestDist = Int.MAX_VALUE
        for (i in presets.indices) {
            val d = kotlin.math.abs(presets[i] - value)
            if (d < bestDist) { bestDist = d; best = i }
        }
        return best
    }

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
