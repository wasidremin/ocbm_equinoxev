package wasidremin.gmccpa

import kotlin.math.roundToInt

/**
 * Edge-scribble calibration solver. Pure Kotlin, no Android — unit-tested.
 *
 * ## What the user does
 * On a full-panel window they paint the DEAD glass with a finger: along the sloped top edge,
 * the swooping right cutout, and (for completeness) the straight left and bottom edges. Strokes
 * are raw touch points in window pixels.
 *
 * ## What the solver does
 * Each edge owns a zone of the window (top: y < 45% h, bottom: y > 55% h, left: x < 40% w,
 * right: x > 60% w; corners vote for both edges). Per 1/48-wide band it takes the extreme dead
 * paint (deepest y for top, shallowest for bottom, rightmost for left, leftmost for right),
 * median-smooths across neighbours, and clamps to the seed (the live display-cutout bounds, so
 * an untraced edge falls back to what the OS reports rather than to zero).
 *
 * The left and bottom edges on this panel are straight, so they collapse to their median. The
 * CarPlay rectangle is then the maximal-area axis-aligned [L,R]x[T,B] with L/B pinned to those
 * medians, T under the top profile and R inside the right profile. The strip above T is the
 * custom top bar's space; the bar background spans the full width while its content keeps clear
 * via the cutout inset path ([DisplayGeometry.clusterRightEdge]).
 *
 * A safety margin shrinks the solved rect on every side: paint marks where the finger WAS, and
 * a fingertip is wider than the true glass edge.
 */
object ScribbleSolver {
    data class Pt(val x: Float, val y: Float)
    /** Visible-bounds seed in window pixels (from the live display cutout at trace time). */
    data class Seed(val l: Float, val t: Float, val r: Float, val b: Float)
    data class Result(
        /** Visible CarPlay rectangle, window pixels. */
        val l: Float, val t: Float, val r: Float, val b: Float,
        /** Fraction of bands with enough paint, per edge — guides the user to gaps. */
        val coverage: Map<String, Float>,
    )

    const val BANDS = 48
    /** Number of central bands (10%..90%) where points are allowed to vote for an edge. */
    private const val ACTIVE_BANDS = 38f
    const val MIN_POINTS_PER_BAND = 3
    const val SAFETY_PX = 8f

    fun solve(w: Float, h: Float, seed: Seed, strokes: List<List<Pt>>): Result {
        if (w <= 0 || h <= 0) return Result(seed.l, seed.t, seed.r, seed.b, emptyMap())
        val pts = strokes.flatten()
        // Per-band extremes of dead paint.
        val topMax = Array(BANDS) { mutableListOf<Float>() }
        val botMin = Array(BANDS) { mutableListOf<Float>() }
        val leftMax = Array(BANDS) { mutableListOf<Float>() }
        val rightMin = Array(BANDS) { mutableListOf<Float>() }
        for (p in pts) {
            if (p.x.isNaN() || p.y.isNaN()) continue
            // Central-range gates: a full-width top stroke must not vote in the right profile
            // (its points sit at y ~= the top, saying nothing about mid-panel visibility), and
            // symmetrically a full-height side stroke must not vote top/bottom. Corners are still
            // covered: both adjacent edges trace into the gated range.
            val centralX = p.x > w * 0.10f && p.x < w * 0.90f
            val centralY = p.y > h * 0.10f && p.y < h * 0.90f
            if (p.y < h * 0.45f && centralX) topMax[colOf(p.x, w)].add(p.y)
            if (p.y > h * 0.55f && centralX) botMin[colOf(p.x, w)].add(p.y)
            if (p.x < w * 0.40f && centralY) leftMax[rowOf(p.y, h)].add(p.x)
            if (p.x > w * 0.60f && centralY) rightMin[rowOf(p.y, h)].add(p.x)
        }
        // Extreme per band, then median-smooth over ±2 bands for finger jitter. Bands without
        // enough paint keep the seed: a missing edge is "OS knows best", not "no glass".
        val top = smooth(topMax.map { m -> if (m.size >= MIN_POINTS_PER_BAND) m.max() else null }, seed.t, true)
        val bot = smooth(botMin.map { m -> if (m.size >= MIN_POINTS_PER_BAND) m.min() else null }, seed.b, false)
        val left = smooth(leftMax.map { m -> if (m.size >= MIN_POINTS_PER_BAND) m.max() else null }, seed.l, true)
        val right = smooth(rightMin.map { m -> if (m.size >= MIN_POINTS_PER_BAND) m.min() else null }, seed.r, false)
        val coverage = mapOf(
            "left" to cover(leftMax), "top" to cover(topMax),
            "right" to cover(rightMin), "bottom" to cover(botMin))
        // No paint at all: the seed stands exactly (no safety margin on OS-reported bounds).
        if (coverage.values.all { it <= 0f })
            return Result(seed.l, seed.t, seed.r, seed.b, coverage)
        val L = median(left).coerceIn(0f, w - 2)
        val B = median(bot).coerceIn(2f, h)
        if (L >= w - 2 || B <= 2) return Result(seed.l, seed.t, seed.r, seed.b, coverage)
        // Column/row profiles over the candidate range; missing bands read the seed.
        val topProf = FloatArray(BANDS) { c -> top[c] }
        val rightProf = FloatArray(BANDS) { r -> right[r] }
        // The rect's top must sit BELOW the deepest dead paint in every spanned column
        // (visible glass starts under the paint), so for each candidate R the top is DERIVED
        // as that maximum — searching T freely degenerates to the seed, because a higher T
        // only ever shrinks the area. R then trades width against the slope it spans.
        var bestT = seed.t.coerceIn(0f, B - 1)
        var bestR = seed.r.coerceIn(L + 1, w)
        var bestArea = -1f
        val cL = colOf(L, w).coerceIn(0, BANDS - 1)
        for (r in rightProf.filter { it in L..w }.plus(seed.r).distinct()) {
            val cR = colOf(r - 0.5f, w).coerceIn(0, BANDS - 1)
            if (cR < cL) continue
            var t = seed.t
            for (c in cL..cR) t = maxOf(t, topProf[c])
            t = t.coerceIn(0f, B - 1)
            if (r - L < 640 || B - t < 480) continue
            if (!rightClears(r, t, B, rightProf, h)) continue
            val area = (r - L) * (B - t)
            if (area > bestArea) { bestArea = area; bestT = t; bestR = r }
        }
        if (bestArea < 0) return Result(seed.l, seed.t, seed.r, seed.b, coverage)
        // Safety margin where paint contributed: the fingertip overshoots the true edge.
        // Untraced sides keep the seed exactly — the OS-reported bound needs no margin.
        val l = if (coverage.getValue("left") <= 0f) seed.l.coerceIn(0f, w - 2)
            else (L + SAFETY_PX).coerceIn(0f, w - 2)
        val t = if (coverage.getValue("top") <= 0f) seed.t.coerceIn(0f, h - 2)
            else (bestT + SAFETY_PX).coerceIn(0f, h - 2)
        val r = if (coverage.getValue("right") <= 0f) seed.r.coerceIn(l + 1, w)
            else (bestR - SAFETY_PX).coerceIn(l + 1, w)
        val b = if (coverage.getValue("bottom") <= 0f) seed.b.coerceIn(t + 1, h)
            else (B - SAFETY_PX).coerceIn(t + 1, h)
        if (r - l < 640 || b - t < 480) return Result(seed.l, seed.t, seed.r, seed.b, coverage)
        return Result(l, t, r, b, coverage)
    }

    /** R must clear (stay left of) the right profile on every row band the rect spans. */
    private fun rightClears(R: Float, T: Float, B: Float, right: FloatArray, h: Float): Boolean {
        for (r in 0 until BANDS) {
            val bandTop = r * h / BANDS
            val bandBot = (r + 1) * h / BANDS
            if (bandBot <= T || bandTop >= B) continue
            if (R > right[r]) return false
        }
        return true
    }

    private fun colOf(x: Float, w: Float): Int = ((x / w) * BANDS).toInt().coerceIn(0, BANDS - 1)
    private fun rowOf(y: Float, h: Float): Int = ((y / h) * BANDS).toInt().coerceIn(0, BANDS - 1)

    private fun smooth(vals: List<Float?>, seed: Float, upper: Boolean): FloatArray {
        // Fill gaps with the seed first so smoothing never invents glass past it.
        val filled = FloatArray(BANDS) { vals[it] ?: seed }
        return FloatArray(BANDS) { i ->
            val win = ((i - 2)..(i + 2)).filter { it in 0 until BANDS }.map { filled[it] }.sorted()
            val med = win[win.size / 2]
            // Paint can only SHRINK the visible rect past the seed, never grow it.
            if (upper) maxOf(seed, med) else minOf(seed, med)
        }
    }

    private fun median(a: FloatArray): Float {
        val s = a.sorted()
        return s[s.size / 2]
    }

    private fun cover(bands: Array<MutableList<Float>>): Float =
        (bands.count { it.size >= MIN_POINTS_PER_BAND } / ACTIVE_BANDS).coerceAtMost(1f)

    /** Per-mille fractions of (w,h) for prefs. */
    fun toFractions(r: Result, w: Float, h: Float): DisplayPrefs.ScribbleRect =
        DisplayPrefs.ScribbleRect(
            (r.l / w * 1000).roundToInt().coerceIn(0, 1000),
            (r.t / h * 1000).roundToInt().coerceIn(0, 1000),
            (r.r / w * 1000).roundToInt().coerceIn(0, 1000),
            (r.b / h * 1000).roundToInt().coerceIn(0, 1000))
}
