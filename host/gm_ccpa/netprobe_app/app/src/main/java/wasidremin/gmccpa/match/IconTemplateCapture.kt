package wasidremin.gmccpa.match

import android.content.Context
import android.graphics.Bitmap
import wasidremin.gmccpa.ProbeLog
import wasidremin.gmccpa.av.CarPlayFrameGrabber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Android bridge between the capture pipeline and the pure matcher (Phase 4).
 *
 * [IconMatcher] never touches a [Bitmap]; this file owns every Android pixel call: grab a stable
 * frame off [CarPlayFrameGrabber], cut the normalized rect ([nx0], [ny0], [nx1], [ny1] in 0..1 of
 * the frame), convert to grayscale, canonicalize, and file the template in [IconTemplateStore].
 * Runs on the caller's thread — never the UI thread (PixelCopy + latch block).
 */
object IconTemplateCapture {
    private val log = ProbeLog.sub("icontmpl")

    data class CapturedTemplate(val template: IconMatcher.IconTemplate, val dir: java.io.File)

    /**
     * Capture a template from a live stable frame.
     *
     * The rect selects an icon tile in normalized frame coordinates; it is clamped inside the
     * frame and must cover at least 8x8 px after denormalization, or the template would be
     * upsampled noise.
     */
    fun captureTemplateBlocking(
        ctx: Context,
        name: String,
        nx0: Float,
        ny0: Float,
        nx1: Float,
        ny1: Float,
        threshold: Float = IconMatcher.DEFAULT_THRESHOLD,
        timeoutMs: Long = CarPlayFrameGrabber.DEFAULT_STABLE_TIMEOUT_MS,
    ): CapturedTemplate {
        require(nx1 > nx0 && ny1 > ny0) { "template rect must be non-empty" }
        require(nx0 >= 0f && ny0 >= 0f && nx1 <= 1f && ny1 <= 1f) {
            "template rect must be inside 0..1 (got [$nx0,$ny0]-[$nx1,$ny1])"
        }
        val appCtx = ctx.applicationContext
        val surface = wasidremin.gmccpa.av.CarPlayActivity.liveSurfaceView()
            ?: throw IllegalStateException("CarPlay video surface is not ready — return to CarPlay screen first")
        val frame = when (val res = CarPlayFrameGrabber.captureStableFrameBlocking(
            timeoutMs = timeoutMs,
            surfaceView = surface,
        )) {
            is CarPlayFrameGrabber.CaptureResult.Success -> res
            is CarPlayFrameGrabber.CaptureResult.Failure ->
                throw IllegalStateException("${res.reason}: ${res.detail}")
        }
        val template = templateFromBitmap(frame.bitmap, name, nx0, ny0, nx1, ny1, threshold, frame.timestampMs)
        val dir = IconTemplateStore.saveTemplate(appCtx, template)
        log.i("captured icon template \"${template.name}\" from ${frame.width}x${frame.height} frame (${frame.framesSampled} samples)")
        return CapturedTemplate(template, dir)
    }

    /**
     * Pure-rect math over a [Bitmap], split out so the coordinate clamping is reviewable without
     * a device. Reads pixels under the grabber's bitmap lock contract (caller holds no lock; a
     * stable frame's reusable bitmap is only written by the capture worker, which has returned).
     */
    fun templateFromBitmap(
        bitmap: Bitmap,
        name: String,
        nx0: Float,
        ny0: Float,
        nx1: Float,
        ny1: Float,
        threshold: Float,
        nowMs: Long = System.currentTimeMillis(),
    ): IconMatcher.IconTemplate {
        val w = bitmap.width
        val h = bitmap.height
        require(w > 0 && h > 0) { "frame has no pixels" }
        val x0 = (nx0 * w).toInt().coerceIn(0, w - 1)
        val y0 = (ny0 * h).toInt().coerceIn(0, h - 1)
        val x1 = (nx1 * w).toInt().coerceIn(x0 + 1, w)
        val y1 = (ny1 * h).toInt().coerceIn(y0 + 1, h)
        require(x1 - x0 >= 8 && y1 - y0 >= 8) {
            "template rect is ${x1 - x0}x${y1 - y0}px, below the 8x8 minimum"
        }
        val argb = IntArray((x1 - x0) * (y1 - y0))
        bitmap.getPixels(argb, 0, x1 - x0, x0, y0, x1 - x0, y1 - y0)
        val gray = IconMatcher.argbToGrayscale(argb)
        return IconMatcher.IconTemplate.canonicalize(
            name = IconTemplateStore.sanitizeName(name),
            src = gray,
            srcW = x1 - x0,
            srcH = y1 - y0,
            threshold = threshold,
            createdIso = isoNow(nowMs),
        )
    }

    private fun isoNow(nowMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(nowMs))
}
