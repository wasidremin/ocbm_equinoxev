package wasidremin.gmccpa

import kotlin.math.roundToInt

/**
 * The visible glass as an eight-point outline, in window pixels. Pure Kotlin, unit-tested.
 *
 * The driver drags eight dots onto the edge of the visible screen ([ScribbleActivity]): the four
 * corners plus one point on each side, which bends that side into a shallow triangle (a peak or a
 * dip) so a sloped top or a swooping right edge can be followed. Points run clockwise:
 * TL, T, TR, R, BR, B, BL, L.
 *
 * Each side is a two-segment polyline and is read as a function: the top and bottom edges as y of
 * x, the left and right edges as x of y, flat beyond their end corners. A point is on glass when
 * it is below the top, above the bottom, right of the left and left of the right edge. Everything
 * else — the CarPlay rectangle and the top-bar fit — is derived from these four functions.
 *
 * Replaces the 2026-10 finger-painting solver, which kept the INNERMOST paint per band (one stray
 * stroke shrank the picture) and could never grow past the OS cutout bounds.
 */
data class GlassOutline(val pts: List<Pt>) {
    data class Pt(val x: Float, val y: Float)
    data class Rect(val l: Float, val t: Float, val r: Float, val b: Float) {
        val width get() = r - l
        val height get() = b - t
    }

    init { require(pts.size == 8) { "outline needs 8 points, got ${pts.size}" } }

    val tl get() = pts[TL]; val tm get() = pts[TM]; val tr get() = pts[TR]; val rm get() = pts[RM]
    val br get() = pts[BR]; val bm get() = pts[BM]; val bl get() = pts[BL]; val lm get() = pts[LM]

    /** Top edge: y of visible glass's top at [x]. */
    fun top(x: Float): Float = along(x, tl.x, tl.y, tm.x, tm.y, tr.x, tr.y)
    /** Bottom edge: y of visible glass's bottom at [x]. */
    fun bottom(x: Float): Float = along(x, bl.x, bl.y, bm.x, bm.y, br.x, br.y)
    /** Left edge: x of visible glass's left side at [y]. */
    fun left(y: Float): Float = along(y, tl.y, tl.x, lm.y, lm.x, bl.y, bl.x)
    /** Right edge: x of visible glass's right side at [y]. */
    fun right(y: Float): Float = along(y, tr.y, tr.x, rm.y, rm.x, br.y, br.x)

    fun contains(x: Float, y: Float): Boolean =
        y >= top(x) && y <= bottom(x) && x >= left(y) && x <= right(y)

    /** Deepest point of the top edge over [x0, x1] — the clearance a level row there must keep. */
    fun topClearance(x0: Float, x1: Float): Float =
        maxOver(x0, x1, floatArrayOf(tl.x, tm.x, tr.x), ::top)

    /** Leftmost point of the right edge over [y0, y1]. */
    fun rightLimit(y0: Float, y1: Float): Float =
        minOver(y0, y1, floatArrayOf(tr.y, rm.y, br.y), ::right)

    /**
     * Furthest-right x where a block spanning rows [y0, y1] is fully on glass: left of the right
     * edge on those rows, and with the top edge above [y0] over the whole block. Scans leftward
     * from the right-edge limit; returns [floor] when nothing fits.
     */
    fun blockRightEdge(y0: Float, y1: Float, blockW: Float, floor: Float): Float {
        var x = rightLimit(y0, y1)
        val step = 4f
        while (x - blockW > floor) {
            if (topClearance(x - blockW, x) <= y0) return x
            x -= step
        }
        return floor
    }

    /**
     * Largest axis-aligned rectangle on glass, or null if nothing at least [minW] x [minH] fits.
     *
     * For a column span [L, R] the tallest valid rect runs from the deepest top to the shallowest
     * bottom over that span, then is trimmed to the longest run of rows whose left/right edges
     * also clear L and R. A coarse grid over (L, R) is refined once around the best cell.
     */
    fun maxRect(minW: Float = 640f, minH: Float = 480f): Rect? {
        val xs = pts.map { it.x }
        val xMin = xs.min(); val xMax = xs.max()
        if (xMax - xMin < minW) return null
        var best: Rect? = null
        var bestArea = -1f
        fun consider(l: Float, r: Float) {
            if (r - l < minW) return
            val rect = tallestFor(l, r, minH) ?: return
            val area = rect.width * rect.height
            if (area > bestArea) { bestArea = area; best = rect }
        }
        val n = 48
        val span = xMax - xMin
        val step = span / n
        for (i in 0..n / 2) for (j in n / 2..n) consider(xMin + i * step, xMin + j * step)
        val coarse = best ?: return null
        val fine = step / 12f
        for (i in -12..12) for (j in -12..12) consider(coarse.l + i * fine, coarse.r + j * fine)
        return best
    }

    private fun tallestFor(l: Float, r: Float, minH: Float): Rect? {
        val t0 = topClearance(l, r)
        val b0 = minOver(l, r, floatArrayOf(bl.x, bm.x, br.x), ::bottom)
        if (b0 - t0 < minH) return null
        // Longest run of rows in [t0, b0] where both side edges clear the span.
        val rows = 240
        val dy = (b0 - t0) / rows
        var runStart = -1
        var bestA = 0; var bestB = -1
        for (k in 0..rows) {
            val y = t0 + k * dy
            val ok = left(y) <= l && right(y) >= r
            if (ok) {
                if (runStart < 0) runStart = k
                if (k - runStart > bestB - bestA) { bestA = runStart; bestB = k }
            } else runStart = -1
        }
        if (bestB < 0) return null
        val t = t0 + bestA * dy
        val b = t0 + bestB * dy
        return if (b - t >= minH) Rect(l, t, r, b) else null
    }

    /** Per-mille fractions of the trace window, for prefs. */
    fun toPerMille(w: Float, h: Float): IntArray = IntArray(16) { i ->
        val p = pts[i / 2]
        val v = if (i % 2 == 0) p.x / w else p.y / h
        (v * 1000f).roundToInt().coerceIn(0, 1000)
    }

    companion object {
        const val TL = 0; const val TM = 1; const val TR = 2; const val RM = 3
        const val BR = 4; const val BM = 5; const val BL = 6; const val LM = 7

        fun fromPerMille(v: IntArray, w: Float, h: Float): GlassOutline? {
            if (v.size != 16 || v.any { it !in 0..1000 }) return null
            return GlassOutline(List(8) { i -> Pt(v[2 * i] / 1000f * w, v[2 * i + 1] / 1000f * h) })
        }

        /** A rectangle with each side point at its midpoint — the starting outline. */
        fun box(l: Float, t: Float, r: Float, b: Float): GlassOutline {
            val cx = (l + r) / 2f; val cy = (t + b) / 2f
            return GlassOutline(listOf(Pt(l, t), Pt(cx, t), Pt(r, t), Pt(r, cy),
                Pt(r, b), Pt(cx, b), Pt(l, b), Pt(l, cy)))
        }

        /** Piecewise-linear a→m→b, read as a function of the first coordinate; flat beyond ends. */
        private fun along(u: Float, au: Float, av: Float, mu: Float, mv: Float, bu: Float, bv: Float): Float {
            if (u <= au) return av
            if (u >= bu) return bv
            return if (u <= mu) lerp(u, au, av, mu, mv) else lerp(u, mu, mv, bu, bv)
        }

        private fun lerp(u: Float, u0: Float, v0: Float, u1: Float, v1: Float): Float =
            if (u1 - u0 <= 1e-3f) maxOf(v0, v1) else v0 + (v1 - v0) * (u - u0) / (u1 - u0)

        // A piecewise-linear function's extremes over an interval sit at its ends or a vertex.
        private fun maxOver(a: Float, b: Float, verts: FloatArray, f: (Float) -> Float): Float {
            var m = maxOf(f(a), f(b))
            for (v in verts) if (v in a..b) m = maxOf(m, f(v))
            return m
        }

        private fun minOver(a: Float, b: Float, verts: FloatArray, f: (Float) -> Float): Float {
            var m = minOf(f(a), f(b))
            for (v in verts) if (v in a..b) m = minOf(m, f(v))
            return m
        }
    }
}
