package wasidremin.gmccpa.match

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Icon template matching for CarPlay launcher verification (Phase 3).
 *
 * Deliberately Android-free: templates and search images are normalized grayscale `FloatArray`s
 * in 0..1, so every decision here runs on plain JVM JUnit with zero stubs. The Android bridge
 * ([android.graphics.Bitmap] -> grayscale) lives with the capture code; only pure arrays cross
 * that boundary.
 *
 * Matching is zero-mean normalized cross-correlation (NCC): invariant to uniform brightness and
 * contrast shifts between the template capture and the live frame, which is exactly the drift a
 * head-unit panel shows across day/night and warm/cold backlight.
 */
object IconMatcher {

    /** Canonical template resolution. Every template is resampled to this on insert. */
    const val TEMPLATE_W = 32
    const val TEMPLATE_H = 32

    /** Default pass threshold on the NCC score in [-1, 1]. */
    const val DEFAULT_THRESHOLD = 0.85f

    /** Templates flatter than this variance carry no shape and are rejected at insert. */
    const val MIN_VARIANCE = 1e-6f

    data class IconTemplate(
        val name: String,
        val width: Int,
        val height: Int,
        val pixels: FloatArray,
        val threshold: Float = DEFAULT_THRESHOLD,
        val sourceCaptureId: String? = null,
        val createdIso: String? = null,
    ) {
        init {
            require(name.isNotBlank()) { "template name must not be blank" }
            require(width > 0 && height > 0) { "template dimensions must be positive" }
            require(pixels.size == width * height) {
                "pixels.size=${pixels.size} != ${width}x$height"
            }
            require(threshold in -1f..1f) { "threshold must be in [-1, 1]" }
            require(pixels.all { it.isFinite() }) { "template pixels must all be finite" }
            require(variance(pixels) >= MIN_VARIANCE) {
                "template \"$name\" is near-constant (variance below $MIN_VARIANCE); it would match everywhere"
            }
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("name", name)
            put("width", width)
            put("height", height)
            put("threshold", threshold.toDouble())
            put("sourceCaptureId", sourceCaptureId ?: JSONObject.NULL)
            put("createdIso", createdIso ?: JSONObject.NULL)
            put("pixels", JSONArray().apply { pixels.forEach { put(it.toDouble()) } })
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IconTemplate) return false
            return name == other.name && width == other.width && height == other.height &&
                threshold == other.threshold && sourceCaptureId == other.sourceCaptureId &&
                createdIso == other.createdIso && pixels.contentEquals(other.pixels)
        }

        override fun hashCode(): Int {
            var h = name.hashCode()
            h = 31 * h + width
            h = 31 * h + height
            h = 31 * h + threshold.hashCode()
            h = 31 * h + (sourceCaptureId?.hashCode() ?: 0)
            h = 31 * h + (createdIso?.hashCode() ?: 0)
            h = 31 * h + pixels.contentHashCode()
            return h
        }

        companion object {
            fun fromJson(obj: JSONObject): IconTemplate {
                val w = obj.optInt("width", 0)
                val h = obj.optInt("height", 0)
                val arr = obj.optJSONArray("pixels")
                require(arr != null) { "template JSON is missing \"pixels\"" }
                return IconTemplate(
                    name = obj.optString("name", ""),
                    width = w,
                    height = h,
                    pixels = FloatArray(arr.length()) { i -> arr.optDouble(i).toFloat() },
                    threshold = obj.optDouble("threshold", DEFAULT_THRESHOLD.toDouble()).toFloat(),
                    sourceCaptureId = if (obj.isNull("sourceCaptureId")) null
                        else obj.optString("sourceCaptureId").takeIf { it.isNotEmpty() },
                    createdIso = if (obj.isNull("createdIso")) null
                        else obj.optString("createdIso").takeIf { it.isNotEmpty() },
                )
            }

            /** Insert path: resample any grayscale source to canonical size, then validate. */
            fun canonicalize(
                name: String,
                src: FloatArray,
                srcW: Int,
                srcH: Int,
                threshold: Float = DEFAULT_THRESHOLD,
                sourceCaptureId: String? = null,
                createdIso: String? = null,
            ): IconTemplate {
                require(src.size == srcW * srcH) { "src.size=${src.size} != ${srcW}x$srcH" }
                val pixels = if (srcW == TEMPLATE_W && srcH == TEMPLATE_H) src.copyOf()
                else resampleGrayscale(src, srcW, srcH, TEMPLATE_W, TEMPLATE_H)
                return IconTemplate(name, TEMPLATE_W, TEMPLATE_H, pixels, threshold, sourceCaptureId, createdIso)
            }
        }
    }

    data class SearchRegion(val x: Int, val y: Int, val width: Int, val height: Int)

    data class MatchResult(
        val templateName: String,
        val score: Float,
        val x: Int,
        val y: Int,
        val threshold: Float,
        val passed: Boolean,
    )

    /** Zero-mean NCC of two equal-length patches in [-1, 1]; 0 when either side is constant. */
    fun normalizedCrossCorrelation(tmpl: FloatArray, patch: FloatArray): Float {
        val n = min(tmpl.size, patch.size)
        if (n == 0) return Float.NaN
        var sumT = 0.0
        var sumP = 0.0
        for (i in 0 until n) {
            sumT += tmpl[i]
            sumP += patch[i]
        }
        val meanT = sumT / n
        val meanP = sumP / n
        var cov = 0.0
        var varT = 0.0
        var varP = 0.0
        for (i in 0 until n) {
            val dt = tmpl[i] - meanT
            val dp = patch[i] - meanP
            cov += dt * dp
            varT += dt * dt
            varP += dp * dp
        }
        if (varT <= 0.0 || varP <= 0.0) return 0f
        return (cov / sqrt(varT * varP)).toFloat().coerceIn(-1f, 1f)
    }

    fun variance(pixels: FloatArray): Float {
        if (pixels.isEmpty()) return 0f
        var sum = 0.0
        for (v in pixels) sum += v
        val mean = sum / pixels.size
        var ssd = 0.0
        for (v in pixels) {
            val d = v - mean
            ssd += d * d
        }
        return (ssd / pixels.size).toFloat()
    }

    /**
     * Bilinear resample of a normalized grayscale image. Pure function of the arrays, so the
     * canonicalize path is exactly what the tests exercise.
     */
    fun resampleGrayscale(src: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): FloatArray {
        require(src.size == srcW * srcH) { "src.size=${src.size} != ${srcW}x$srcH" }
        require(srcW > 0 && srcH > 0 && dstW > 0 && dstH > 0) { "dimensions must be positive" }
        val out = FloatArray(dstW * dstH)
        if (srcW == 1 && srcH == 1) {
            out.fill(src[0])
            return out
        }
        val sx = if (dstW == 1) 0f else (srcW - 1f) / (dstW - 1f)
        val sy = if (dstH == 1) 0f else (srcH - 1f) / (dstH - 1f)
        for (dy in 0 until dstH) {
            val fy = dy * sy
            val y0 = fy.toInt().coerceIn(0, srcH - 1)
            val y1 = min(y0 + 1, srcH - 1)
            val wy = fy - fy.toInt()
            for (dx in 0 until dstW) {
                val fx = dx * sx
                val x0 = fx.toInt().coerceIn(0, srcW - 1)
                val x1 = min(x0 + 1, srcW - 1)
                val wx = fx - fx.toInt()
                val top = src[y0 * srcW + x0] * (1f - wx) + src[y0 * srcW + x1] * wx
                val bot = src[y1 * srcW + x0] * (1f - wx) + src[y1 * srcW + x1] * wx
                out[dy * dstW + dx] = top * (1f - wy) + bot * wy
            }
        }
        return out
    }

    /** ITU-R BT.601 luma of packed ARGB ints, normalized to 0..1. */
    fun argbToGrayscale(argb: IntArray): FloatArray = FloatArray(argb.size) { i ->
        val px = argb[i]
        val r = (px ushr 16) and 0xFF
        val g = (px ushr 8) and 0xFF
        val b = px and 0xFF
        (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f
    }

    /**
     * Slide [template] over [target] (row-major, [targetW]x[targetH]) with step [stride],
     * restricted to [region] when given. Returns the best-scoring placement; `passed` is
     * `score >= template.threshold`. A template larger than the search area cannot be compared:
     * score is `NaN`, `passed` is false.
     */
    fun findBest(
        template: IconTemplate,
        target: FloatArray,
        targetW: Int,
        targetH: Int,
        stride: Int = 1,
        region: SearchRegion? = null,
    ): MatchResult {
        require(target.size == targetW * targetH) { "target.size=${target.size} != ${targetW}x$targetH" }
        require(stride >= 1) { "stride must be >= 1" }
        val rx = region?.x?.coerceIn(0, targetW) ?: 0
        val ry = region?.y?.coerceIn(0, targetH) ?: 0
        val rw = region?.width?.let { min(it, targetW - rx) } ?: (targetW - rx)
        val rh = region?.height?.let { min(it, targetH - ry) } ?: (targetH - ry)
        if (template.width > rw || template.height > rh) {
            return MatchResult(template.name, Float.NaN, rx, ry, template.threshold, passed = false)
        }
        val tw = template.width
        val th = template.height
        val patch = FloatArray(tw * th)
        var best = -2f
        var bx = rx
        var by = ry
        var y = ry
        while (y + th <= ry + rh) {
            var x = rx
            while (x + tw <= rx + rw) {
                for (py in 0 until th) {
                    System.arraycopy(target, (y + py) * targetW + x, patch, py * tw, tw)
                }
                val s = normalizedCrossCorrelation(template.pixels, patch)
                if (s > best) {
                    best = s
                    bx = x
                    by = y
                }
                // Early exit: a perfect match cannot be beaten.
                if (best >= 1f) {
                    return MatchResult(template.name, 1f, bx, by, template.threshold, passed = true)
                }
                x += stride
            }
            y += stride
        }
        return MatchResult(template.name, best, bx, by, template.threshold, best >= template.threshold)
    }

    /**
     * Crop [src] to ([x0], [y0], [cropW], [cropH]) then resample to [dstW]x[dstH]. Used to build
     * the fixed-size working image the launcher searches: same content rect the heuristic
     * classifier uses (x in 12%..95%, y in 12%..88%), so matcher and classifier always agree on
     * what "the content area" is.
     */
    fun cropAndResample(
        src: FloatArray,
        srcW: Int,
        srcH: Int,
        x0: Int,
        y0: Int,
        cropW: Int,
        cropH: Int,
        dstW: Int,
        dstH: Int,
    ): FloatArray {
        require(src.size == srcW * srcH) { "src.size=${src.size} != ${srcW}x$srcH" }
        require(x0 >= 0 && y0 >= 0 && cropW > 0 && cropH > 0) { "crop must be positive" }
        require(x0 + cropW <= srcW && y0 + cropH <= srcH) { "crop overflows source" }
        val crop = FloatArray(cropW * cropH)
        for (y in 0 until cropH) {
            System.arraycopy(src, (y0 + y) * srcW + x0, crop, y * cropW, cropW)
        }
        return resampleGrayscale(crop, cropW, cropH, dstW, dstH)
    }

    /**
     * Mean absolute difference of two equal-length patches (0..1). Companion to the NCC score:
     * the stability gate in [wasidremin.gmccpa.av.CarPlayFrameGrabber] speaks MAD, so launcher
     * verification reports both.
     */
    fun meanAbsoluteDifference(a: FloatArray, b: FloatArray): Float {
        val n = min(a.size, b.size)
        if (n == 0) return 0f
        var sum = 0f
        for (i in 0 until n) sum += abs(a[i] - b[i])
        return sum / n
    }
}
