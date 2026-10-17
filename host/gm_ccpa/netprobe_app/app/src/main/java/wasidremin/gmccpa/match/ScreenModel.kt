package wasidremin.gmccpa.match

import kotlin.math.roundToInt

/**
 * Fixed-scale model of the CarPlay screen. Pure JVM (no android.*) so it unit-tests on real PNGs.
 *
 * Every search runs on the full frame box-averaged by [SCALE], and every template is cut from a
 * frame at that same scale, so template and target pixels are always the same size. (v98 matched
 * a 32x32 canonical template against a 192x72 working image — a ~2x scale mismatch that scored
 * 0.33 against its own source frame.)
 *
 * Geometry measured on 2754x966 captures from the Equinox EV, iOS 26.6.2, 2026-10-15.
 */
object ScreenModel {
    const val SCALE = 4

    data class NRect(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    /** Recent-apps strip icons (left edge). */
    val STRIP_ROI = NRect(0.00f, 0.25f, 0.06f, 0.78f)
    /** Home grid area (everything right of the strip, above the page dots). */
    val GRID_ROI = NRect(0.06f, 0.08f, 0.985f, 0.86f)
    /** Bottom button of the strip: 3x3-grid glyph on Dashboard, square glyph elsewhere. */
    val GLYPH_ROI = NRect(0.00f, 0.84f, 0.06f, 0.99f)

    const val STRIP_CENTER_NX = 0.029f
    const val TOGGLE_NX = 0.029f
    const val TOGGLE_NY = 0.915f
    const val STRIP_MAX_NX = 0.06f
    const val GLYPH_MIN_NY = 0.84f

    const val GLYPH_DASHBOARD = "glyph.dashboard"
    const val GLYPH_OTHER = "glyph.other"

    val ROLES = listOf("maps", "music", "spotify", "phone")

    /** Template edge in FULL-RES px. Icons measure ~88 px (strip), ~180 px (grid). */
    enum class Kind(val prefix: String, val sizeFullPx: Int, val threshold: Float, val stride: Int) {
        STRIP("strip", 72, 0.85f, 1),
        GRID("grid", 150, 0.85f, 3),
        GLYPH("glyph", 60, 0.85f, 1);

        val side: Int get() = (sizeFullPx.toFloat() / SCALE).roundToInt()
        val roi: NRect get() = when (this) {
            STRIP -> STRIP_ROI
            GRID -> GRID_ROI
            GLYPH -> GLYPH_ROI
        }
    }

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

    fun kindForPoint(nx: Float, ny: Float): Kind = when {
        nx < STRIP_MAX_NX && ny >= GLYPH_MIN_NY -> Kind.GLYPH
        nx < STRIP_MAX_NX -> Kind.STRIP
        else -> Kind.GRID
    }

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

    /**
     * Cut a [kind] template centred at normalized (nx, ny). Strip and glyph templates are pinned
     * to the strip's centre column so a sloppy tap can't cut off the icon edge.
     */
    fun cutTemplate(
        g: Gray,
        kind: Kind,
        role: String,
        nx: Float,
        ny: Float,
        sourceCaptureId: String?,
        createdIso: String?,
    ): IconMatcher.IconTemplate {
        val n = kind.side
        val cx = if (kind == Kind.GRID) nx else STRIP_CENTER_NX
        val x0 = (cx * g.width - n / 2f).roundToInt().coerceIn(0, g.width - n)
        val y0 = (ny * g.height - n / 2f).roundToInt().coerceIn(0, g.height - n)
        val px = FloatArray(n * n)
        for (y in 0 until n) System.arraycopy(g.px, (y0 + y) * g.width + x0, px, y * n, n)
        return IconMatcher.IconTemplate(
            name = templateName(kind, role),
            width = n,
            height = n,
            pixels = px,
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
    ): SurfaceEvidence {
        if (dashboard == null || other == null) return SurfaceEvidence(Surface.UNKNOWN, null, null)
        val d = find(g, dashboard, GLYPH_ROI, 1)
        val o = find(g, other, GLYPH_ROI, 1)
        val surface = when {
            d.passed && d.score > o.score -> Surface.DASHBOARD
            o.passed && o.score > d.score -> Surface.NOT_DASHBOARD
            else -> Surface.UNKNOWN
        }
        fun safe(score: Float): Float? = if (score.isFinite()) score else null
        return SurfaceEvidence(surface, safe(d.score), safe(o.score))
    }

    /** Dashboard vs not-Dashboard from the strip's bottom glyph. Cannot tell Home grid from an app. */
    fun surface(g: Gray, dashboard: IconMatcher.IconTemplate?, other: IconMatcher.IconTemplate?): Surface =
        surfaceDetailed(g, dashboard, other).surface

    /** Mean absolute difference of two same-size frames (0..1). Screen-to-screen is ~0.15. */
    fun changed(a: Gray, b: Gray): Float {
        require(a.width == b.width && a.height == b.height) { "frame size changed" }
        return IconMatcher.meanAbsoluteDifference(a.px, b.px)
    }
}
