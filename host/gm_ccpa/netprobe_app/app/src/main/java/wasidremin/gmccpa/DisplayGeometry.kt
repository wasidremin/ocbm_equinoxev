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
    fun clusterRightEdge(viewWidth: Float, barHeight: Float, rightInsetPx: Int,
                         endIndent: Float = barHeight * 0.55f): Float =
        viewWidth - endIndent - rightInsetPx.coerceAtLeast(0).toFloat()

    /**
     * Slope of the panel's top-edge cutout wedge: glass starts at y = slopeM * x.
     *
     * Vehicle-proven on the Equinox EV (2026-10-15 geometry log): the cutout bounding rects are
     * `Rect(0, 0 - 2629, 167)` (top wedge) and `Rect(2629, 0 - 2932, 1134)` (right block) on a
     * 2914x1134 window, safe insets 0/167/285/0. The panel's top edge is a straight slope down
     * left-to-right, so the wedge's bounding box gives the glass line directly: rise 167 over
     * run 2629 (m = 0.06352). The bar lives at y 0..168 — its BOTTOM row is on glass almost
     * everywhere, its top progressively is not — which is why bar content hangs from computed
     * tops near the bottom instead of centering in the rect (centering put the whole right
     * cluster on missing glass at x 2000-2540, where the slope eats y 0..127-161). Zero when
     * degenerate: no wedge means a rectangular panel and every shift below is a no-op. Pure.
     */
    fun wedgeSlopeM(wedgeRight: Int, wedgeBottom: Int): Float =
        if (wedgeRight > 0 && wedgeBottom > 0) wedgeBottom.toFloat() / wedgeRight else 0f

    /**
     * Downward shift so a content box whose centered top is [centeredTop] clears the slope at
     * position [x], with [margin] of glass to spare. Zero on a flat panel (or when the centered
     * position already clears) — the emulator renders byte-identical to the centered layout.
     * A level row of icons uses its LAST (rightmost, steepest-slope) x so the row stays level.
     * Pure and unit-tested.
     */
    fun levelShift(centeredTop: Float, x: Float, slopeM: Float, margin: Float): Float =
        ((slopeM * x + margin) - centeredTop).coerceAtLeast(0f)

    /**
     * Right-edge x for the cluster such that a [contentH]-tall block hung from the slope
     * clearance still ends above the bar bottom: E <= (barH - 2*margin - contentH) / slopeM.
     * Takes the further-left of that and [clusterRightEdge] (which already keeps the cluster
     * off the fully-cut right block with the car-like end indent). On the vehicle (168px bar,
     * m = 0.06352, 50px content) this lands near x 1600 — mid-panel, where ~50px of glass
     * exists — instead of x 2540, where 7px exists. Degenerate slope or content falls back to
     * [clusterRightEdge]. May return less than the left block end (or negative) for absurd
     * inputs; the caller hides the cluster then instead of overlapping. Pure and unit-tested.
     */
    fun clusterRightEdgeOnGlass(viewWidth: Float, barHeight: Float, rightInsetPx: Int,
                                slopeM: Float, contentH: Float, margin: Float,
                                endIndent: Float = barHeight * 0.55f): Float {
        val e0 = clusterRightEdge(viewWidth, barHeight, rightInsetPx, endIndent)
        if (slopeM <= 0f || contentH <= 0f) return e0
        return minOf(e0, (barHeight - 2 * margin - contentH) / slopeM)
    }

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
     * One level row of square app tiles hung from the panel slope, split into two groups by an
     * extra gap after [breakAfter] tiles.
     *
     * [size] is the tile edge, [pitch] centre-to-centre spacing WITHIN a group, [x0] the first
     * tile's LEFT edge, [top] the shared tile top, [end] the right edge of the last tile.
     * [groupGap] is the extra space inserted before the first tile of the second group (every
     * tile at index >= [breakAfter] is shifted right by it), and [dividerX] is the centre of
     * that gap, where the caller draws the car-vs-ours divider. [breakAfter] 0 or >= count means
     * one group and no gap.
     */
    data class TileRow(
        val size: Float,
        val pitch: Float,
        val x0: Float,
        val top: Float,
        val end: Float,
        val groupGap: Float = 0f,
        val breakAfter: Int = 0,
        val dividerX: Float = 0f,
    ) {
        /** Left edge of tile [i]. [groupGap] is 0 when there is no break, so this is safe always. */
        fun tileLeft(i: Int): Float = x0 + i * pitch + if (i >= breakAfter) groupGap else 0f
    }

    /**
     * Largest tile row that is entirely on glass.
     *
     * Tiles are bottom-justified in the bar (a [margin] clear of the hairline) because on a
     * sloped panel the bottom row of the bar is the part that is always visible; the row is
     * level, so the LAST (rightmost, steepest-slope) tile decides: its top must clear
     * `slopeM * end + margin`. With pitch `(1 + gapFrac) * size` and `x0` fixed this is linear
     * in size: `size * (1 + slopeM * ((count - 1) * (1 + gapFrac) + groupGapFrac + 1)) <= barH -
     * 2.5 * margin - slopeM * x0` — the `groupGapFrac` term is the extra gap inserted after
     * [breakAfter] tiles to separate the two tile families (0 when [breakAfter] is 0 or >=
     * count). Capped at [maxFrac] of the bar height (a flat panel gets tiles as large as the
     * car's own icons, never larger) and floored at [minFrac] so an absurd slope shrinks the
     * tiles rather than making them unreadable. When [rightClusterRoom] says the right cluster
     * would be crushed, the row shrinks by [shrink] steps (down to [minFrac]) until the cluster
     * is at least [minClusterScale] of its natural width. Zero slope gives a plain row at
     * [maxFrac]. Pure and unit-tested.
     */
    fun topBarTiles(
        barHeight: Float,
        slopeM: Float,
        count: Int,
        x0: Float,
        margin: Float,
        gapFrac: Float = 0.28f,
        maxFrac: Float = 0.66f,
        minFrac: Float = 0.34f,
        rightClusterRoom: ((end: Float) -> Float)? = null,
        minClusterScale: Float = 0.8f,
        shrink: Float = 0.94f,
        breakAfter: Int = 0,
        groupGapFrac: Float = 0f,
    ): TileRow {
        val n = count.coerceAtLeast(1)
        val m = slopeM.coerceAtLeast(0f)
        val hasBreak = breakAfter in 1 until n
        val gg = if (hasBreak) groupGapFrac.coerceAtLeast(0f) else 0f
        val spans = (n - 1) * (1f + gapFrac) + gg + 1f
        val limit = (barHeight - 2.5f * margin - m * x0) / (1f + m * spans)
        var size = minOf(barHeight * maxFrac, limit).coerceAtLeast(barHeight * minFrac)
        fun rowOf(s: Float): TileRow {
            val pitch = s * (1f + gapFrac)
            val groupGap = s * gg
            val end = x0 + (n - 1) * pitch + s + groupGap
            // Divider sits mid-way between the last tile of group A and the first of group B.
            val aRight = x0 + (breakAfter - 1) * pitch + s
            val bLeft = x0 + breakAfter * pitch + groupGap
            val dividerX = if (hasBreak) (aRight + bLeft) / 2f else 0f
            return TileRow(s, pitch, x0, barHeight - 1.5f * margin - s, end, groupGap, if (hasBreak) breakAfter else 0, dividerX)
        }
        var row = rowOf(size)
        if (rightClusterRoom != null) {
            var guard = 0
            while (rightClusterRoom(row.end) < minClusterScale && size > barHeight * minFrac && guard++ < 24) {
                size = (size * shrink).coerceAtLeast(barHeight * minFrac)
                row = rowOf(size)
            }
        }
        return row
    }

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
     * Justified placement of the bar's two clusters inside the *usable* glass.
     *
     * The panel's right third is dead glass (slope + cutout): on the Equinox EV a 47px-tall
     * row only fits left of x ~1640 of a 2914px window, so a right cluster pinned to the
     * window edge (x 2537) would be invisible — and a cluster pinned to x 1640 *looks* like
     * "everything shoved left" unless the placement is visibly justified. This solver makes
     * the justification explicit and calculated:
     *
     * - LEFT cluster: left-justified to its measured start (car photo: first centre 0.95 x bar
     *   height, pitch 0.88 x height — see [leftBlockEnd]). Never shrunk; the car's own icons
     *   keep their measured size.
     * - RIGHT cluster: right-justified to [clusterRightEdgeOnGlass] — the furthest-right x
     *   where a [contentH]-tall block still ends above the bar bottom on visible glass. That
     *   edge, not the window edge, is the right margin: anything further right is missing
     *   glass, so the empty band beyond it is physics, not a layout choice.
     * - MIDDLE gap: absorbs whatever is left. [minGap] (default 0.8 x bar height) is sacred:
     *   when the slot is tight the right cluster shrinks to preserve the gap instead of
     *   touching the left icons, so the tile row and the status cluster always read as clearly
     *   separate zones (widened from 0.5 x 2026-10-23 — the clock/temp sat too close to the
     *   tiles for the two to read apart).
     *
     * Returns left/right edges plus the right-cluster scale; scale 0 hides the cluster when
     * even the gap does not fit. Pure and unit-tested.
     */
    data class TopBarLayout(
        val leftEnd: Float,
        val rightEdge: Float,
        val rightScale: Float,
        val rightStart: Float,
        val gap: Float,
    )

    fun topBarLayout(
        viewWidth: Float,
        barHeight: Float,
        rightInsetPx: Int,
        slopeM: Float,
        contentH: Float,
        margin: Float,
        leftEnd: Float,
        naturalWidth: Float,
        minGap: Float = barHeight * 0.8f,
        endIndent: Float = barHeight * 0.55f,
    ): TopBarLayout {
        val rightEdge = clusterRightEdgeOnGlass(viewWidth, barHeight, rightInsetPx, slopeM, contentH, margin, endIndent)
        val scale = clusterFitScale(rightEdge - leftEnd - minGap, naturalWidth)
        val rightStart = rightEdge - naturalWidth * scale
        return TopBarLayout(leftEnd, rightEdge, scale, rightStart, rightStart - leftEnd)
    }

    /**
     * The rectangle the settings UI may occupy: the CarPlay calibration space.
     *
     * The video pipeline latches a block (rail + strip + picture + dead-glass margins) at
     * subscribe time and centers it in the live window via [fitLatched]; the picture itself
     * is then inset by the presentation [DisplayPrefs.Controls] dp sliders via [content].
     * Settings previously filled the whole window with only system-bar safe insets, so on the
     * vehicle its right edge sat under the same dead glass the video calibration keeps clear
     * of. Constraining the settings host to this frame — the centered latch, further inset
     * by the same presentation controls (scaled, like the video path) — keeps every settings
     * row on the same visible glass the CarPlay picture uses. The inner scroller still
     * scrolls vertically; this frame only bounds the horizontal/outer extent so nothing is
     * parked under the slope or cutout. Pure and unit-tested.
     */
    fun settingsFrame(
        rootW: Int,
        rootH: Int,
        latchedW: Int,
        latchedH: Int,
        controls: DisplayPrefs.Controls,
        density: Float,
    ): Box {
        if (rootW <= 0 || rootH <= 0) return Box(0, 0, 1, 1)
        val fit = fitLatched(rootW, rootH, latchedW.coerceAtLeast(1), latchedH.coerceAtLeast(1))
        val blockW = (latchedW.coerceAtLeast(1) * fit.scale).roundToInt().coerceAtLeast(1)
        val blockH = (latchedH.coerceAtLeast(1) * fit.scale).roundToInt().coerceAtLeast(1)
        val l = (controls.left * density * fit.scale).roundToInt().coerceAtLeast(0)
        val r = (controls.right * density * fit.scale).roundToInt().coerceAtLeast(0)
        val t = (controls.top * density * fit.scale).roundToInt().coerceAtLeast(0)
        val b = (controls.bottom * density * fit.scale).roundToInt().coerceAtLeast(0)
        val x = (fit.x + l).coerceIn(0, (rootW - 1).coerceAtLeast(0))
        val y = (fit.y + t).coerceIn(0, (rootH - 1).coerceAtLeast(0))
        val w = (blockW - l - r).coerceIn(1, (rootW - x).coerceAtLeast(1))
        val h = (blockH - t - b).coerceIn(1, (rootH - y).coerceAtLeast(1))
        return Box(x, y, w, h)
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
