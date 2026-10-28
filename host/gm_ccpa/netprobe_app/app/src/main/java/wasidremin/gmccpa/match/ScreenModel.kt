package wasidremin.gmccpa.match

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Model of the CarPlay screen. Pure JVM (no android.*) so it unit-tests on real PNGs.
 *
 * Every search runs on the full frame box-averaged by [SCALE], and every template is cut from a
 * frame at that same scale, so template and target pixels are always the same size. (v98 matched
 * a 32x32 canonical template against a 192x72 working image — a ~2x scale mismatch that scored
 * 0.33 against its own source frame.)
 *
 * CarPlay lays its UI out in points, so the strip, its icons and the Home toggle keep their PIXEL
 * size whatever the coded width (2754- and 2342-wide frames both draw 87–88 px strip icons) and
 * change only with the UI scale. Reporting a physical size changes that scale: 240 mm for 2470 px
 * drew everything 1.276x larger (2026-10-28), every fixed-size template scored ~0.4, and the top
 * bar's launches all came back NOT_FOUND. So templates are stored at the default scale
 * ([Kind.side]) and resized with [atScale], and every region and tap point comes from a [Geometry]
 * for the frame's scale, measured from the frame itself by [measureUi] when the prediction
 * ([expectedUi]) does not match.
 *
 * Geometry measured on 2754x966 captures from the Equinox EV, iOS 26.6.2, 2026-10-15.
 */
object ScreenModel {
    const val SCALE = 4

    /** The capture size the pixel constants below were measured on (at CarPlay's default scale). */
    const val REF_W = 2754
    const val REF_H = 966

    // Full-resolution pixels at the default UI scale; [Geometry] multiplies them by its scale.
    private const val STRIP_CENTER_PX = 0.029f * REF_W
    private const val STRIP_RIGHT_PX = 0.06f * REF_W
    /** Below the clock. The first icon's top moves little as the scale grows (270 px at 1.0, 282 at 1.276). */
    private const val STRIP_TOP_PX = 150f
    private const val TOGGLE_FROM_BOTTOM_PX = 0.085f * REF_H
    private const val GLYPH_TOP_FROM_BOTTOM_PX = 0.16f * REF_H
    private const val GLYPH_BOTTOM_MARGIN_PX = 0.01f * REF_H
    private const val GRID_TOP_PX = 0.08f * REF_H
    private const val GRID_BOTTOM_FROM_BOTTOM_PX = 0.14f * REF_H
    private const val GRID_RIGHT_MARGIN_PX = 0.015f * REF_W

    /** The smallest template side matching is attempted with (1/[SCALE] px). */
    private const val MIN_SIDE = 4

    /**
     * Pixels per millimetre CarPlay assumes when the head unit reports no physical size. Measured,
     * not documented: 240 mm for 2470 px (10.29 px/mm) drew the UI at 1.276x its default
     * (2026-10-28, `cap5_strip_2470_ui83.png`), so the default is 10.29 / 1.276.
     */
    const val DEFAULT_PX_PER_MM = 8.066f

    /** The UI scales [measureUi] searches. */
    const val UI_MIN = 0.5f
    const val UI_MAX = 2.0f

    data class NRect(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    const val GLYPH_DASHBOARD = "glyph.dashboard"
    const val GLYPH_OTHER = "glyph.other"

    val ROLES = listOf("maps", "music", "spotify", "phone")

    /** Template edge in FULL-RES px at the default scale. Icons measure ~88 px (strip), ~180 px (grid). */
    enum class Kind(val prefix: String, val sizeFullPx: Int, val threshold: Float, val stride: Int) {
        STRIP("strip", 72, 0.85f, 1),
        GRID("grid", 150, 0.85f, 3),
        GLYPH("glyph", 60, 0.85f, 1);

        /** Stored template side (1/[SCALE] px): templates are always kept at the default scale. */
        val side: Int get() = (sizeFullPx.toFloat() / SCALE).roundToInt()
    }

    /**
     * Where the strip, the grid and the Home toggle sit on a [frameW]x[frameH] frame (full-res
     * px) when CarPlay draws its UI [ui] times its default size. Left-hand things are measured
     * from the left edge, the toggle and glyph from the bottom, so both scale about their own edge.
     */
    class Geometry(val ui: Float, val frameW: Int, val frameH: Int) {
        init {
            require(ui > 0f && frameW > 0 && frameH > 0) { "bad geometry ui=$ui ${frameW}x$frameH" }
        }

        private fun fromLeft(px: Float) = (px * ui / frameW).coerceIn(0f, 1f)
        private fun fromTop(px: Float) = (px * ui / frameH).coerceIn(0f, 1f)
        private fun fromBottom(px: Float) = (1f - px * ui / frameH).coerceIn(0f, 1f)

        val stripCenterNx: Float = fromLeft(STRIP_CENTER_PX)
        val stripMaxNx: Float = fromLeft(STRIP_RIGHT_PX)
        val glyphMinNy: Float = fromBottom(GLYPH_TOP_FROM_BOTTOM_PX)
        val toggleNx: Float = stripCenterNx
        val toggleNy: Float = fromBottom(TOGGLE_FROM_BOTTOM_PX)

        /** Recent-apps strip icons (left edge), between the clock and the toggle. */
        val stripRoi = NRect(0f, fromTop(STRIP_TOP_PX), stripMaxNx, glyphMinNy)
        /** Home grid area (everything right of the strip, above the page dots). */
        val gridRoi = NRect(stripMaxNx, fromTop(GRID_TOP_PX), 1f - GRID_RIGHT_MARGIN_PX * ui / frameW,
            fromBottom(GRID_BOTTOM_FROM_BOTTOM_PX))
        /** Bottom button of the strip: 3x3-grid glyph on Dashboard, square glyph elsewhere. */
        val glyphRoi = NRect(0f, glyphMinNy, stripMaxNx, fromBottom(GLYPH_BOTTOM_MARGIN_PX))

        fun roi(kind: Kind): NRect = when (kind) {
            Kind.STRIP -> stripRoi
            Kind.GRID -> gridRoi
            Kind.GLYPH -> glyphRoi
        }

        /** Template side on this frame (1/[SCALE] px). */
        fun side(kind: Kind): Int = max(MIN_SIDE, (kind.sizeFullPx * ui / SCALE).roundToInt())

        fun kindForPoint(nx: Float, ny: Float): Kind = when {
            nx < stripMaxNx && ny >= glyphMinNy -> Kind.GLYPH
            nx < stripMaxNx -> Kind.STRIP
            else -> Kind.GRID
        }

        override fun toString(): String = "ui=%.3f %dx%d".format(java.util.Locale.US, ui, frameW, frameH)
    }

    /** The geometry of [g] at UI scale [ui]. */
    fun geometry(g: Gray, ui: Float = 1f): Geometry = Geometry(ui, g.width * SCALE, g.height * SCALE)

    /** The 2754x966 captures the built-in templates were cut from, at the default scale. */
    val REFERENCE = Geometry(1f, REF_W, REF_H)

    /**
     * CarPlay's UI scale relative to its default, for a head unit that reports [physicalWidthMm]
     * for [widthPx] coded pixels. Reporting none (0) is the default, 1.0.
     */
    fun expectedUi(widthPx: Int, physicalWidthMm: Int): Float =
        if (widthPx <= 0 || physicalWidthMm <= 0) 1f else widthPx / (physicalWidthMm * DEFAULT_PX_PER_MM)

    /** The physical width that asks CarPlay for [percent] of its default UI size; 0 (report none) at 100. */
    fun physicalWidthMmFor(widthPx: Int, percent: Int): Int =
        if (percent == 100 || percent <= 0 || widthPx <= 0) 0
        else (widthPx * 100f / (percent * DEFAULT_PX_PER_MM)).roundToInt().coerceAtLeast(1)

    enum class Surface { DASHBOARD, NOT_DASHBOARD, UNKNOWN }

    data class Hit(val name: String, val score: Float, val nx: Float, val ny: Float, val passed: Boolean)

    class Gray(val width: Int, val height: Int, val px: FloatArray) {
        init {
            require(width > 0 && height > 0 && px.size == width * height) {
                "bad Gray ${width}x$height/${px.size}"
            }
        }
    }

    fun templateName(kind: Kind, role: String): String = "${kind.prefix}.${role.lowercase()}"

    /** Box-average packed ARGB to 0..1 grayscale (BT.601) at 1/[SCALE]. */
    fun downscale(argb: IntArray, w: Int, h: Int): Gray {
        require(argb.size == w * h) { "argb.size=${argb.size} != ${w}x$h" }
        val ow = w / SCALE
        val oh = h / SCALE
        val out = FloatArray(ow * oh)
        val inv = 1f / (SCALE * SCALE * 255f)
        for (oy in 0 until oh) {
            for (ox in 0 until ow) {
                var sum = 0f
                for (dy in 0 until SCALE) {
                    val row = (oy * SCALE + dy) * w + ox * SCALE
                    for (dx in 0 until SCALE) {
                        val p = argb[row + dx]
                        sum += 0.299f * ((p ushr 16) and 0xFF) +
                            0.587f * ((p ushr 8) and 0xFF) +
                            0.114f * (p and 0xFF)
                    }
                }
                out[oy * ow + ox] = sum * inv
            }
        }
        return Gray(ow, oh, out)
    }

    fun searchRegion(g: Gray, r: NRect): IconMatcher.SearchRegion {
        val x = (r.x0 * g.width).toInt().coerceIn(0, g.width - 1)
        val y = (r.y0 * g.height).toInt().coerceIn(0, g.height - 1)
        val x1 = (r.x1 * g.width).toInt().coerceIn(x + 1, g.width)
        val y1 = (r.y1 * g.height).toInt().coerceIn(y + 1, g.height)
        return IconMatcher.SearchRegion(x, y, x1 - x, y1 - y)
    }

    /** [t], stored at the default scale, resized for a UI drawn [ui] times larger. */
    fun atScale(t: IconMatcher.IconTemplate, ui: Float): IconMatcher.IconTemplate {
        val w = max(MIN_SIDE, (t.width * ui).roundToInt())
        val h = max(MIN_SIDE, (t.height * ui).roundToInt())
        if (w == t.width && h == t.height) return t
        return t.copy(width = w, height = h, pixels = resize(t.pixels, t.width, t.height, w, h))
    }

    /**
     * Bilinear resize with pixel CENTRES aligned, so content scales by exactly dst/src. (The
     * corner-aligned [IconMatcher.resampleGrayscale] scales by (dst-1)/(src-1): 18 -> 23 px is
     * 1.29x, not 1.28x, and the scale sweep read 1.31 for a 1.276 frame.)
     */
    private fun resize(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        val out = FloatArray(dw * dh)
        val kx = sw.toFloat() / dw
        val ky = sh.toFloat() / dh
        for (y in 0 until dh) {
            val fy = ((y + 0.5f) * ky - 0.5f).coerceIn(0f, sh - 1f)
            val y0 = fy.toInt()
            val y1 = minOf(y0 + 1, sh - 1)
            val wy = fy - y0
            for (x in 0 until dw) {
                val fx = ((x + 0.5f) * kx - 0.5f).coerceIn(0f, sw - 1f)
                val x0 = fx.toInt()
                val x1 = minOf(x0 + 1, sw - 1)
                val wx = fx - x0
                val top = src[y0 * sw + x0] * (1f - wx) + src[y0 * sw + x1] * wx
                val bot = src[y1 * sw + x0] * (1f - wx) + src[y1 * sw + x1] * wx
                out[y * dw + x] = top * (1f - wy) + bot * wy
            }
        }
        return out
    }

    /** [t] with light and dark swapped. */
    private fun inverted(t: IconMatcher.IconTemplate): IconMatcher.IconTemplate =
        t.copy(pixels = FloatArray(t.pixels.size) { 1f - t.pixels[it] })

    /**
     * Cut a [kind] template centred at normalized (nx, ny) on a frame drawn at [geo]'s scale, and
     * store it at the default scale. Strip and glyph templates are pinned to the strip's centre
     * column so a sloppy tap can't cut off the icon edge.
     */
    fun cutTemplate(
        g: Gray,
        kind: Kind,
        role: String,
        nx: Float,
        ny: Float,
        sourceCaptureId: String?,
        createdIso: String?,
        geo: Geometry = geometry(g),
    ): IconMatcher.IconTemplate {
        val n = geo.side(kind).coerceAtMost(minOf(g.width, g.height))
        val cx = if (kind == Kind.GRID) nx else geo.stripCenterNx
        val x0 = (cx * g.width - n / 2f).roundToInt().coerceIn(0, g.width - n)
        val y0 = (ny * g.height - n / 2f).roundToInt().coerceIn(0, g.height - n)
        val cut = FloatArray(n * n)
        for (y in 0 until n) System.arraycopy(g.px, (y0 + y) * g.width + x0, cut, y * n, n)
        val side = kind.side
        return IconMatcher.IconTemplate(
            name = templateName(kind, role),
            width = side,
            height = side,
            pixels = if (n == side) cut else resize(cut, n, n, side, side),
            threshold = kind.threshold,
            sourceCaptureId = sourceCaptureId,
            createdIso = createdIso,
        )
    }

    /** Coarse search at [stride], then a stride-1 refine around the best spot. Centre is normalized. */
    fun find(g: Gray, t: IconMatcher.IconTemplate, roi: NRect, stride: Int): Hit {
        val reg = searchRegion(g, roi)
        val coarse = IconMatcher.findBest(t, g.px, g.width, g.height, stride, reg)
        var score = coarse.score
        var bx = coarse.x
        var by = coarse.y
        if (stride > 1 && !coarse.score.isNaN()) {
            val fx = maxOf(0, coarse.x - stride)
            val fy = maxOf(0, coarse.y - stride)
            val fine = IconMatcher.findBest(
                t, g.px, g.width, g.height, 1,
                IconMatcher.SearchRegion(fx, fy, t.width + 2 * stride, t.height + 2 * stride),
            )
            if (!fine.score.isNaN() && fine.score > score) {
                score = fine.score
                bx = fine.x
                by = fine.y
            }
        }
        val passed = !score.isNaN() && score >= t.threshold
        return Hit(t.name, score, (bx + t.width / 2f) / g.width, (by + t.height / 2f) / g.height, passed)
    }

    /** [t] (stored at the default scale) searched for in its kind's region of [geo]. */
    fun find(g: Gray, t: IconMatcher.IconTemplate, kind: Kind, geo: Geometry): Hit =
        search(g, atScale(t, geo.ui), kind, geo.roi(kind), kind.stride)

    /**
     * The toggle glyph is a monochrome symbol drawn dark-on-light in light mode and light-on-dark
     * at night, and NCC changes sign when the polarity does (the night capture of 2026-10-28
     * scored 0.28), so a glyph is also tried inverted. App icons are not inverted at night.
     */
    private fun search(g: Gray, t: IconMatcher.IconTemplate, kind: Kind, roi: NRect, stride: Int): Hit {
        val hit = find(g, t, roi, stride)
        if (kind != Kind.GLYPH) return hit
        val inv = find(g, inverted(t), roi, stride)
        return if (inv.score > hit.score) inv else hit
    }

    /** The kind an anchor or target template is searched as, from its name. */
    fun kindOf(t: IconMatcher.IconTemplate): Kind? = Kind.values().firstOrNull { t.name.startsWith(it.prefix + ".") }

    /** How well a frame fits UI scale [ui]: the best anchor there, and whether it passed. */
    data class UiFit(val ui: Float, val score: Float, val anchor: String?, val passed: Boolean)

    /**
     * The best score at scale [ui] over [anchors]: the strip.* and glyph.* templates, which the
     * strip shows on every CarPlay screen. Grid templates are ignored (only the Home grid has them).
     */
    fun anchorFit(g: Gray, anchors: List<IconMatcher.IconTemplate>, ui: Float, stride: Int = 1): UiFit {
        val geo = geometry(g, ui)
        var best = UiFit(ui, Float.NEGATIVE_INFINITY, null, false)
        for (t in anchors) {
            val kind = kindOf(t)?.takeIf { it != Kind.GRID } ?: continue
            val hit = search(g, atScale(t, ui), kind, geo.roi(kind), stride)
            if (hit.score.isFinite() && hit.score > best.score) best = UiFit(ui, hit.score, t.name, hit.passed)
        }
        return best
    }

    /**
     * The UI scale [g] was drawn at, read from the strip icons, or from the toggle glyph when no
     * strip icon matches. [UiFit.passed] is false when nothing matched at any scale; ties go to the
     * scale nearest [prefer].
     */
    fun measureUi(g: Gray, anchors: List<IconMatcher.IconTemplate>, prefer: Float = 1f): UiFit {
        var best = UiFit(prefer, Float.NEGATIVE_INFINITY, null, false)
        for (kind in listOf(Kind.STRIP, Kind.GLYPH)) {
            val set = anchors.filter { kindOf(it) == kind }
            if (set.isEmpty()) continue
            val fit = sweep(g, set, kind.side, prefer)
            if (fit.passed) return fit
            if (fit.score > best.score) best = fit
        }
        return best
    }

    /**
     * Templates are whole pixels at 1/[SCALE], so the sweep steps through template SIDES (every
     * scale between two sides draws the same template): every side at stride 2, the best and its
     * neighbours again at stride 1, then a parabola through those three for the scale in between.
     */
    private fun sweep(g: Gray, anchors: List<IconMatcher.IconTemplate>, base: Int, prefer: Float): UiFit {
        val sides = maxOf(MIN_SIDE, ceil(base * UI_MIN).toInt())..(base * UI_MAX).toInt()
        fun better(a: UiFit, b: UiFit): Boolean =
            a.score > b.score + 1e-6f || (abs(a.score - b.score) <= 1e-6f && abs(a.ui - prefer) < abs(b.ui - prefer))
        var coarse = UiFit(prefer, Float.NEGATIVE_INFINITY, null, false)
        for (n in sides) {
            val f = anchorFit(g, anchors, n.toFloat() / base, stride = 2)
            if (better(f, coarse)) coarse = f
        }
        if (coarse.anchor == null) return coarse
        val n = (coarse.ui * base).roundToInt()
        val at = (n - 1..n + 1).filter { it in sides }.associateWith { anchorFit(g, anchors, it.toFloat() / base, stride = 1) }
        val mid = at.getValue(n)
        val lo = at[n - 1]?.score
        val hi = at[n + 1]?.score
        var off = 0f
        if (lo != null && hi != null && lo.isFinite() && hi.isFinite()) {
            val curve = lo - 2f * mid.score + hi
            if (curve < 0f) off = (0.5f * (lo - hi) / curve).coerceIn(-0.5f, 0.5f)
        }
        return mid.copy(ui = (n + off) / base)
    }

    /**
     * Both glyph scores for one frame, plus the classification. Scores are null when the
     * template is missing or the match score is non-finite — never NaN.
     * Both scores always come from the SAME supplied frame.
     */
    data class SurfaceEvidence(
        val surface: Surface,
        val dashboardScore: Float?,
        val otherScore: Float?,
    )

    /** Detailed glyph result; [surface] delegates to this with identical rules. */
    fun surfaceDetailed(
        g: Gray,
        dashboard: IconMatcher.IconTemplate?,
        other: IconMatcher.IconTemplate?,
        geo: Geometry = geometry(g),
    ): SurfaceEvidence {
        if (dashboard == null || other == null) return SurfaceEvidence(Surface.UNKNOWN, null, null)
        val d = find(g, dashboard, Kind.GLYPH, geo)
        val o = find(g, other, Kind.GLYPH, geo)
        val surface = when {
            d.passed && d.score > o.score -> Surface.DASHBOARD
            o.passed && o.score > d.score -> Surface.NOT_DASHBOARD
            else -> Surface.UNKNOWN
        }
        fun safe(score: Float): Float? = if (score.isFinite()) score else null
        return SurfaceEvidence(surface, safe(d.score), safe(o.score))
    }

    /** Dashboard vs not-Dashboard from the strip's bottom glyph. Cannot tell Home grid from an app. */
    fun surface(
        g: Gray,
        dashboard: IconMatcher.IconTemplate?,
        other: IconMatcher.IconTemplate?,
        geo: Geometry = geometry(g),
    ): Surface = surfaceDetailed(g, dashboard, other, geo).surface

    /** Mean absolute difference of two same-size frames (0..1). Screen-to-screen is ~0.15. */
    fun changed(a: Gray, b: Gray): Float {
        require(a.width == b.width && a.height == b.height) { "frame size changed" }
        return IconMatcher.meanAbsoluteDifference(a.px, b.px)
    }
}
