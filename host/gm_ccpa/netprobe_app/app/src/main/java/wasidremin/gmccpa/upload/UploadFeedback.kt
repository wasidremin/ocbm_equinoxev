package wasidremin.gmccpa.upload

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import wasidremin.gmccpa.EqButtonStyle
import wasidremin.gmccpa.EqTheme
import wasidremin.gmccpa.ProbeLog
import wasidremin.gmccpa.av.CarPlayFrameGrabber
import wasidremin.gmccpa.eqButton
import wasidremin.gmccpa.logging.LogExport
import wasidremin.gmccpa.logging.UploadWindow
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Reusable app-wide upload state model (Phase 2.1).
 */
sealed interface UploadState {
    data object Idle : UploadState
    data object Preparing : UploadState
    data class InProgress(val bytesSent: Long, val bytesTotal: Long?) : UploadState
    data object Finalizing : UploadState
    data class Success(val serverRef: String?) : UploadState
    data class Queued(val reason: String) : UploadState
    data class Failed(val message: String, val retryable: Boolean) : UploadState
}

/**
 * Wraps a socket/request [OutputStream] and emits progress from bytes actually written to the
 * underlying stream, throttled to ~10 emissions/sec (100 ms) or every 64 KB, whichever is less
 * frequent (i.e. both time and byte thresholds must be satisfied before an intermediate emission,
 * plus the final completion write).
 */
class CountingRequestOutputStream(
    delegate: OutputStream,
    private val bytesTotal: Long?,
    private val minIntervalMs: Long = 100L,
    private val minDeltaBytes: Long = 64L * 1024L,
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val onProgress: (bytesSent: Long, bytesTotal: Long?) -> Unit,
) : FilterOutputStream(delegate) {

    var bytesWritten: Long = 0L
        private set
    private var lastEmitBytes: Long = 0L
    private var lastEmitAtMs: Long = -minIntervalMs

    override fun write(b: Int) {
        out.write(b)
        recordWritten(1L)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        // Write in chunks of at most 16 KB so large single-buffer writes still report smooth socket progress
        val step = 16 * 1024
        var cursor = off
        var remaining = len
        while (remaining > 0) {
            val n = min(step, remaining)
            out.write(b, cursor, n)
            cursor += n
            remaining -= n
            recordWritten(n.toLong())
        }
    }

    private fun recordWritten(delta: Long) {
        bytesWritten += delta
        val now = clockMs()
        val timeElapsed = now - lastEmitAtMs
        val bytesDelta = bytesWritten - lastEmitBytes
        val completed = bytesTotal != null && bytesWritten >= bytesTotal
        // "whichever is less frequent": intermediate emissions require BOTH >= 100ms AND >= 64KB
        // (or first emission / completion).
        val firstEmit = lastEmitBytes == 0L && bytesWritten > 0L
        val thresholdMet = timeElapsed >= minIntervalMs && bytesDelta >= minDeltaBytes
        if (firstEmit || thresholdMet || completed) {
            lastEmitBytes = bytesWritten
            lastEmitAtMs = now
            onProgress(bytesWritten, bytesTotal)
        }
    }
}

/**
 * Icon glyphs drawn on [UploadActionButton] so no external vector/drawable resources are needed.
 */
enum class UploadIcon {
    UPLOAD,
    CAMERA,
    CHECK,
    CLOCK,
    WARNING,
    CONNECTION,
}

/**
 * Reusable fixed-size upload action button (Phase 2.3).
 *
 * - Keeps identical measured width/height across every [UploadState] (zero layout shift).
 * - Smoothly animates determinate progress fill toward the target fraction.
 * - Holds [UploadState.Success] for 2000 ms, then automatically returns to [UploadState.Idle].
 * - Tappable in [UploadState.Idle], [UploadState.Queued] (force retry), and [UploadState.Failed]
 *   when `retryable == true`.
 * - Announces [UploadState.Success] and [UploadState.Failed] via Toast/status callback and sets
 *   per-state `contentDescription` for accessibility.
 */
class UploadActionButton(
    ctx: Context,
    private val defaultLabel: String,
    private val defaultIcon: UploadIcon = UploadIcon.UPLOAD,
    private val announceToast: Boolean = true,
    private val onStatusAnnounce: ((String) -> Unit)? = null,
    private val onTrigger: () -> Unit,
) : View(ctx) {

    var state: UploadState = UploadState.Idle
        private set

    var onRetry: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var displayedFraction = 0f
    private var targetFraction = 0f
    private var progressAnim: ValueAnimator? = null
    private var spinnerAngle = 0f
    private var spinnerAnim: ValueAnimator? = null
    private val resetToIdleRunnable = Runnable { setState(UploadState.Idle) }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = EqTheme.Weight.MEDIUM.face
    }
    private val rect = RectF()
    private val clipPath = Path()

    init {
        isClickable = true
        isFocusable = true
        updateAccessibilityAndClickability(UploadState.Idle)
    }

    fun setState(newState: UploadState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { setState(newState) }
            return
        }
        val prev = state
        state = newState
        mainHandler.removeCallbacks(resetToIdleRunnable)

        when (newState) {
            is UploadState.Idle -> {
                stopSpinner()
                animateProgressTo(0f, immediate = true)
            }
            is UploadState.Preparing -> {
                animateProgressTo(0f, immediate = true)
                startSpinner()
            }
            is UploadState.InProgress -> {
                val total = newState.bytesTotal
                if (total != null && total > 0L) {
                    stopSpinner()
                    val frac = (newState.bytesSent.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    animateProgressTo(frac, immediate = false)
                } else {
                    animateProgressTo(0f, immediate = true)
                    startSpinner()
                }
            }
            is UploadState.Finalizing -> {
                animateProgressTo(1f, immediate = false)
                startSpinner()
            }
            is UploadState.Success -> {
                stopSpinner()
                animateProgressTo(1f, immediate = true)
                if (prev !is UploadState.Success) {
                    val msg = if (!newState.serverRef.isNullOrBlank()) {
                        "$defaultLabel: Uploaded (${newState.serverRef})"
                    } else {
                        "$defaultLabel: Uploaded"
                    }
                    announce(msg)
                }
                mainHandler.postDelayed(resetToIdleRunnable, 2000L)
            }
            is UploadState.Queued -> {
                stopSpinner()
                animateProgressTo(0f, immediate = true)
                if (prev !is UploadState.Queued) {
                    announce("$defaultLabel: Queued (${newState.reason})")
                }
            }
            is UploadState.Failed -> {
                stopSpinner()
                animateProgressTo(0f, immediate = true)
                if (prev !is UploadState.Failed) {
                    announce("$defaultLabel failed: ${newState.message}")
                }
            }
        }
        updateAccessibilityAndClickability(newState)
        invalidate()
    }

    private fun announce(message: String) {
        onStatusAnnounce?.invoke(message)
        if (announceToast) {
            runCatching {
                Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateAccessibilityAndClickability(s: UploadState) {
        val interactive = when (s) {
            is UploadState.Idle -> true
            is UploadState.Queued -> true
            is UploadState.Failed -> s.retryable
            else -> false
        }
        isEnabled = interactive
        isClickable = interactive
        contentDescription = when (s) {
            is UploadState.Idle -> defaultLabel
            is UploadState.Preparing -> "$defaultLabel: Preparing"
            is UploadState.InProgress -> {
                val total = s.bytesTotal
                if (total != null && total > 0L) {
                    val pct = ((s.bytesSent * 100L) / total).coerceIn(0L, 100L)
                    "$defaultLabel: Uploading $pct percent"
                } else {
                    "$defaultLabel: Uploading ${formatBytes(s.bytesSent)}"
                }
            }
            is UploadState.Finalizing -> "$defaultLabel: Finishing upload"
            is UploadState.Success -> "$defaultLabel: Uploaded"
            is UploadState.Queued -> "$defaultLabel: Queued — will retry. Tap to retry now"
            is UploadState.Failed -> if (s.retryable) {
                "$defaultLabel: Failed (${s.message}). Tap to retry"
            } else {
                "$defaultLabel: Failed (${s.message})"
            }
        }
        setOnClickListener {
            when (val cur = state) {
                is UploadState.Idle -> onTrigger()
                is UploadState.Queued -> (onRetry ?: onTrigger).invoke()
                is UploadState.Failed -> if (cur.retryable) (onRetry ?: onTrigger).invoke()
                else -> Unit
            }
        }
    }

    private fun animateProgressTo(target: Float, immediate: Boolean) {
        val clamped = target.coerceIn(0f, 1f)
        targetFraction = clamped
        progressAnim?.cancel()
        if (immediate || !ValueAnimator.areAnimatorsEnabled() || abs(displayedFraction - clamped) < 0.005f) {
            displayedFraction = clamped
            invalidate()
            return
        }
        progressAnim = ValueAnimator.ofFloat(displayedFraction, clamped).apply {
            duration = 180L
            addUpdateListener {
                displayedFraction = (it.animatedValue as Float).coerceIn(0f, 1f)
                invalidate()
            }
            start()
        }
    }

    private fun startSpinner() {
        if (spinnerAnim?.isRunning == true) return
        if (!ValueAnimator.areAnimatorsEnabled()) return
        spinnerAnim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                spinnerAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopSpinner() {
        spinnerAnim?.cancel()
        spinnerAnim = null
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacks(resetToIdleRunnable)
        stopSpinner()
        progressAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        textPaint.textSize = EqTheme.pxf(18f)
        // Measure against the widest possible label so the button never shifts size across states.
        val candidates = listOf(
            defaultLabel,
            "Preparing...",
            "Uploading 100%",
            "Uploading... 99.9 MB",
            "Finishing...",
            "Uploaded",
            "Queued — will retry",
            "Server unreachable (retry)",
        )
        val maxTextW = candidates.maxOf { textPaint.measureText(it) }
        val iconSpace = EqTheme.pxf(32f)
        val padH = EqTheme.pxf(56f)
        val wantW = max(EqTheme.px(280f), (maxTextW + iconSpace + padH).roundToInt())
        val wantH = EqTheme.px(64f)
        setMeasuredDimension(
            resolveSize(wantW, widthMeasureSpec),
            resolveSize(wantH, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = h / 2f
        rect.set(0f, 0f, w, h)

        val s = state
        val baseFill: Int
        val strokeColor: Int
        val contentColor: Int

        when (s) {
            is UploadState.Idle -> {
                baseFill = EqTheme.ACCENT
                strokeColor = Color.TRANSPARENT
                contentColor = 0xFF06101E.toInt()
            }
            is UploadState.Preparing,
            is UploadState.InProgress,
            is UploadState.Finalizing -> {
                baseFill = 0xFF142034.toInt()
                strokeColor = 0x554C9BFF
                contentColor = EqTheme.TEXT
            }
            is UploadState.Success -> {
                baseFill = EqTheme.OK
                strokeColor = Color.TRANSPARENT
                contentColor = 0xFF051810.toInt()
            }
            is UploadState.Queued -> {
                baseFill = 0xFF1C232D.toInt()
                strokeColor = 0x44FFFFFF
                contentColor = EqTheme.TEXT_DIM
            }
            is UploadState.Failed -> {
                baseFill = 0x28FF6B61
                strokeColor = EqTheme.DANGER
                contentColor = EqTheme.DANGER
            }
        }

        bgPaint.color = baseFill
        canvas.drawRoundRect(rect, r, r, bgPaint)

        // Determinate progress track fill across the button
        val showProgressFill = (s is UploadState.InProgress && s.bytesTotal != null && s.bytesTotal > 0L) ||
            s is UploadState.Finalizing
        if (showProgressFill && displayedFraction > 0f) {
            canvas.save()
            clipPath.reset()
            clipPath.addRoundRect(rect, r, r, Path.Direction.CW)
            canvas.clipPath(clipPath)
            fillPaint.color = 0x884C9BFF.toInt()
            canvas.drawRect(0f, 0f, w * displayedFraction, h, fillPaint)
            canvas.restore()
        }

        if (strokeColor != Color.TRANSPARENT) {
            strokePaint.color = strokeColor
            strokePaint.strokeWidth = max(1f, EqTheme.pxf(1.5f))
            val inset = strokePaint.strokeWidth / 2f
            rect.set(inset, inset, w - inset, h - inset)
            canvas.drawRoundRect(rect, r - inset, r - inset, strokePaint)
        }

        val labelText = renderLabel(s)
        textPaint.textSize = EqTheme.pxf(18f)
        textPaint.color = contentColor

        val iconSize = EqTheme.pxf(22f)
        val gap = EqTheme.pxf(10f)
        val maxLabelWidth = (w - EqTheme.pxf(40f) - iconSize - gap).coerceAtLeast(40f)
        val clippedLabel = ellipsizeToWidth(labelText, textPaint, maxLabelWidth)
        val textW = textPaint.measureText(clippedLabel)
        val totalContentW = iconSize + gap + textW
        val startX = ((w - totalContentW) / 2f).coerceAtLeast(EqTheme.pxf(16f))
        val centerY = h / 2f

        drawStateIcon(canvas, s, startX + iconSize / 2f, centerY, iconSize / 2f, contentColor)

        val baseline = centerY - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(clippedLabel, startX + iconSize + gap, baseline, textPaint)
    }

    private fun renderLabel(s: UploadState): String = when (s) {
        is UploadState.Idle -> defaultLabel
        is UploadState.Preparing -> "Preparing..."
        is UploadState.InProgress -> {
            val total = s.bytesTotal
            if (total != null && total > 0L) {
                val pct = (displayedFraction * 100f).roundToInt().coerceIn(0, 100)
                "Uploading $pct%"
            } else {
                "Uploading... ${formatBytes(s.bytesSent)}"
            }
        }
        is UploadState.Finalizing -> "Finishing..."
        is UploadState.Success -> "Uploaded"
        is UploadState.Queued -> "Queued — will retry"
        is UploadState.Failed -> s.message.ifBlank { "Upload failed" }
    }

    private fun ellipsizeToWidth(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        var end = text.length
        while (end > 1 && paint.measureText(text, 0, end) + paint.measureText(ellipsis) > maxWidth) {
            end--
        }
        return text.substring(0, end) + ellipsis
    }

    private fun drawStateIcon(canvas: Canvas, s: UploadState, cx: Float, cy: Float, radius: Float, color: Int) {
        iconPaint.color = color
        iconPaint.strokeWidth = max(2f, radius * 0.22f)
        val spinning = s is UploadState.Preparing ||
            (s is UploadState.InProgress && (s.bytesTotal == null || s.bytesTotal <= 0L)) ||
            s is UploadState.Finalizing
        if (spinning) {
            rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(rect, spinnerAngle, 260f, false, iconPaint)
            return
        }
        val icon = when (s) {
            is UploadState.Idle -> defaultIcon
            is UploadState.InProgress -> defaultIcon
            is UploadState.Success -> UploadIcon.CHECK
            is UploadState.Queued -> UploadIcon.CLOCK
            is UploadState.Failed -> UploadIcon.WARNING
            else -> defaultIcon
        }
        when (icon) {
            UploadIcon.CHECK -> {
                canvas.drawLine(cx - radius * 0.65f, cy + radius * 0.05f, cx - radius * 0.15f, cy + radius * 0.55f, iconPaint)
                canvas.drawLine(cx - radius * 0.15f, cy + radius * 0.55f, cx + radius * 0.75f, cy - radius * 0.50f, iconPaint)
            }
            UploadIcon.CLOCK -> {
                canvas.drawCircle(cx, cy, radius * 0.88f, iconPaint)
                canvas.drawLine(cx, cy, cx, cy - radius * 0.50f, iconPaint)
                canvas.drawLine(cx, cy, cx + radius * 0.42f, cy + radius * 0.15f, iconPaint)
            }
            UploadIcon.WARNING -> {
                val path = Path().apply {
                    moveTo(cx, cy - radius * 0.85f)
                    lineTo(cx + radius * 0.85f, cy + radius * 0.75f)
                    lineTo(cx - radius * 0.85f, cy + radius * 0.75f)
                    close()
                }
                canvas.drawPath(path, iconPaint)
                canvas.drawLine(cx, cy - radius * 0.25f, cx, cy + radius * 0.25f, iconPaint)
                canvas.drawPoint(cx, cy + radius * 0.52f, iconPaint)
            }
            UploadIcon.CAMERA -> {
                rect.set(cx - radius * 0.85f, cy - radius * 0.55f, cx + radius * 0.85f, cy + radius * 0.70f)
                canvas.drawRoundRect(rect, radius * 0.2f, radius * 0.2f, iconPaint)
                canvas.drawCircle(cx, cy + radius * 0.08f, radius * 0.35f, iconPaint)
            }
            UploadIcon.CONNECTION -> {
                canvas.drawCircle(cx, cy + radius * 0.55f, radius * 0.12f, iconPaint)
                rect.set(cx - radius * 0.55f, cy, cx + radius * 0.55f, cy + radius * 1.1f)
                canvas.drawArc(rect, 210f, 120f, false, iconPaint)
                rect.set(cx - radius * 0.95f, cy - radius * 0.45f, cx + radius * 0.95f, cy + radius * 1.45f)
                canvas.drawArc(rect, 210f, 120f, false, iconPaint)
            }
            UploadIcon.UPLOAD -> {
                canvas.drawLine(cx, cy + radius * 0.55f, cx, cy - radius * 0.65f, iconPaint)
                canvas.drawLine(cx - radius * 0.45f, cy - radius * 0.20f, cx, cy - radius * 0.65f, iconPaint)
                canvas.drawLine(cx + radius * 0.45f, cy - radius * 0.20f, cx, cy - radius * 0.65f, iconPaint)
                canvas.drawLine(cx - radius * 0.65f, cy + radius * 0.75f, cx + radius * 0.65f, cy + radius * 0.75f, iconPaint)
            }
        }
    }

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> "%.1f KB".format(Locale.US, bytes / 1024.0)
            else -> "%.1f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
        }
    }
}

/**
 * Pure, Android-free upload policy: retry classification, failure mapping, and the multipart
 * envelope math (Phase 2.4).
 *
 * Lives outside [UploadCenter] on purpose: `UploadCenter` initializes `Handler`s on load, so any
 * unit test touching it needs a device. Everything here runs on plain JVM JUnit with zero stubs.
 */
object UploadPolicy {
    const val RETRY_BACKOFF_MS = 1_200L

    open class HttpStatusException(open val code: Int, message: String) : IOException(message)

    fun isTransientNetworkFailure(t: Throwable): Boolean = when (t) {
        is HttpStatusException -> t.code == 408 || t.code == 429 || t.code in 500..599
        is SocketTimeoutException, is UnknownHostException, is java.net.ConnectException -> true
        is IOException -> true
        else -> false
    }

    fun classifyFailure(t: Throwable, allowQueue: Boolean): UploadState {
        return when {
            t is IllegalArgumentException -> UploadState.Failed(t.message ?: "Invalid request", retryable = false)
            t is HttpStatusException && t.code in 400..499 && t.code != 408 && t.code != 429 ->
                UploadState.Failed("HTTP ${t.code}", retryable = false)
            isTransientNetworkFailure(t) && allowQueue ->
                UploadState.Queued(t.message ?: t.javaClass.simpleName)
            isTransientNetworkFailure(t) ->
                UploadState.Failed(t.message ?: "Network unreachable", retryable = true)
            else ->
                UploadState.Failed(t.message ?: t.javaClass.simpleName, retryable = true)
        }
    }

    /** One automatic retry on transient failure, then rethrow. [sleepMs] is injectable for tests. */
    fun <T> executeWithOneRetry(
        backoffMs: Long = RETRY_BACKOFF_MS,
        onRetry: ((Throwable) -> Unit)? = null,
        sleepMs: (Long) -> Unit = { ms -> Thread.sleep(ms) },
        block: () -> T,
    ): T {
        try {
            return block()
        } catch (first: Throwable) {
            if (!isTransientNetworkFailure(first)) throw first
            onRetry?.invoke(first)
            try {
                sleepMs(backoffMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw first
            }
            return block()
        }
    }

    fun buildMultipartEnvelope(
        captureId: String,
        boundary: String,
        jsonBytes: ByteArray,
        pngLength: Long,
    ): Triple<ByteArray, ByteArray, Long> {
        val crlf = "\r\n"
        val prefix = buildString {
            append("--").append(boundary).append(crlf)
            append("Content-Disposition: form-data; name=\"metadata\"; filename=\"metadata.json\"").append(crlf)
            append("Content-Type: application/json; charset=utf-8").append(crlf).append(crlf)
        }.toByteArray(Charsets.UTF_8) + jsonBytes + buildString {
            append(crlf)
            append("--").append(boundary).append(crlf)
            append("Content-Disposition: form-data; name=\"image\"; filename=\"").append(captureId).append(".png\"").append(crlf)
            append("Content-Type: image/png").append(crlf).append(crlf)
        }.toByteArray(Charsets.UTF_8)

        val suffix = "$crlf--$boundary--$crlf".toByteArray(Charsets.UTF_8)
        val total = prefix.size.toLong() + pngLength + suffix.size.toLong()
        return Triple(prefix, suffix, total)
    }
}

/**
 * Shared upload transport, configurable base URL, connection test, and pending-capture queue
 * (Phase 2.4).
 */
object UploadCenter {
    private val log = ProbeLog.sub("upld")
    private const val PREFS = "upload_center_prefs"
    private const val KEY_BASE_URL = "base_url"
    const val CONNECT_TIMEOUT_MS = 10_000
    const val WRITE_READ_TIMEOUT_MS = 60_000
    private const val RETRY_BACKOFF_MS = 1_200L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "upload-center-io").apply { isDaemon = true }
    }
    private val queueListeners = CopyOnWriteArrayList<(Int) -> Unit>()

    fun baseUrl(ctx: Context): String {
        val raw = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BASE_URL, LogExport.DEFAULT_REMOTE_BASE_URL)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: LogExport.DEFAULT_REMOTE_BASE_URL
        return raw.trimEnd('/')
    }

    fun setBaseUrl(ctx: Context, url: String) {
        val cleaned = url.trim().trimEnd('/').ifEmpty { LogExport.DEFAULT_REMOTE_BASE_URL }
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_URL, cleaned)
            .apply()
        log.i("upload base URL updated to $cleaned")
    }

    /**
     * How far back the Logs button reads. Lives with [baseUrl] rather than in [CapturePrefs]: that
     * file is the capture engine's "which scope to run at" knob, and this is a choice about the
     * upload, not about what gets captured. Absent/unreadable values fall back to
     * [UploadWindow.DEFAULT] (the last hour), so an older install gains the shorter range rather
     * than an accidental unbounded upload.
     */
    private const val KEY_LOG_WINDOW = "log_window"

    fun logWindow(ctx: Context): UploadWindow =
        UploadWindow.fromName(
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LOG_WINDOW, null)
        )

    fun setLogWindow(ctx: Context, window: UploadWindow) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOG_WINDOW, window.name)
            .apply()
        log.i("log upload range set to ${window.label} (${window.name})")
    }

    fun addQueueListener(listener: (Int) -> Unit) {
        queueListeners.add(listener)
    }

    fun removeQueueListener(listener: (Int) -> Unit) {
        queueListeners.remove(listener)
    }

    fun pendingCaptureCount(ctx: Context): Int {
        val root = CarPlayFrameGrabber.capturesRootDir(ctx)
        val dirs = (root.listFiles()?.filter { it.isDirectory } ?: emptyList())
        val captureCount = dirs.count { File(it, ".pending_upload").isFile }
        val probeCount = wasidremin.gmccpa.telemetry.ProbeUpload.pendingReportCount(ctx)
        return captureCount + probeCount
    }

    fun notifyQueueChanged(ctx: Context) {
        val count = pendingCaptureCount(ctx)
        mainHandler.post {
            queueListeners.forEach { it(count) }
        }
    }

    fun markCapturePending(ctx: Context, saved: CarPlayFrameGrabber.SavedCapture) {
        runCatching {
            File(saved.dir, ".pending_upload").writeText(saved.id, Charsets.UTF_8)
        }
        notifyQueueChanged(ctx)
    }

    fun clearCapturePending(ctx: Context, saved: CarPlayFrameGrabber.SavedCapture) {
        runCatching {
            File(saved.dir, ".pending_upload").delete()
        }
        notifyQueueChanged(ctx)
    }

    /**
     * Test connectivity to [baseUrl] using [UploadState] semantics, and drain pending captures
     * if reachable.
     */
    fun testConnection(
        ctx: Context,
        onState: (UploadState) -> Unit,
    ) {
        val appCtx = ctx.applicationContext
        emitState(onState, UploadState.Preparing)
        executor.execute {
            val base = baseUrl(appCtx)
            try {
                val probePayload = "{\"device\":\"gmccpa\",\"lines\":[\"gmccpa connection test\"]}"
                    .toByteArray(Charsets.UTF_8)
                val total = probePayload.size.toLong()
                emitState(onState, UploadState.InProgress(0L, total))
                val ref = executeWithOneRetry {
                    val conn = (URL("$base/logs").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = WRITE_READ_TIMEOUT_MS
                        doOutput = true
                        setFixedLengthStreamingMode(total)
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("Accept", "application/json")
                    }
                    try {
                        CountingRequestOutputStream(
                            delegate = conn.outputStream,
                            bytesTotal = total,
                        ) { sent, tot ->
                            emitState(onState, UploadState.InProgress(sent, tot))
                        }.use { out ->
                            out.write(probePayload)
                            out.flush()
                        }
                        emitState(onState, UploadState.Finalizing)
                        val code = conn.responseCode
                        if (code !in 200..299) {
                            throw UploadPolicy.HttpStatusException(code, "HTTP $code")
                        }
                        "HTTP $code"
                    } finally {
                        conn.disconnect()
                    }
                }
                emitState(onState, UploadState.Success(ref))
                drainPendingCaptures(appCtx)
            } catch (t: Throwable) {
                emitState(onState, classifyFailure(t, allowQueue = false))
            }
        }
    }

    /**
     * POST multipart to `{baseUrl}/api/captures` with PNG and JSON sidecar as separate parts.
     * Connects with 10s timeout, 60s write/read timeout, one automatic retry with backoff on
     * transient network failure, then transitions to [UploadState.Queued].
     */
    fun uploadCapture(
        ctx: Context,
        saved: CarPlayFrameGrabber.SavedCapture,
        onState: (UploadState) -> Unit,
    ) {
        val appCtx = ctx.applicationContext
        markCapturePending(appCtx, saved)
        emitState(onState, UploadState.Preparing)
        executor.execute {
            try {
                val ref = uploadCaptureSync(appCtx, saved) { state ->
                    emitState(onState, state)
                }
                clearCapturePending(appCtx, saved)
                emitState(onState, UploadState.Success(ref))
            } catch (t: Throwable) {
                val terminal = classifyFailure(t, allowQueue = true)
                log.w("capture upload ${saved.id} -> $terminal (${t.javaClass.simpleName}: ${t.message})")
                emitState(onState, terminal)
                notifyQueueChanged(appCtx)
            }
        }
    }

    /**
     * Drain any pending captures in `filesDir/captures/<timestamp>/` on app foreground or
     * successful connection test.
     */
    fun drainPendingCaptures(
        ctx: Context,
        onDrainState: ((UploadState) -> Unit)? = null,
    ) {
        val appCtx = ctx.applicationContext
        wasidremin.gmccpa.telemetry.ProbeUpload.enqueueAndDrain(appCtx, onState = onDrainState)
        executor.execute {
            val pending = CarPlayFrameGrabber.listSavedCaptures(appCtx)
                .filter { File(it.dir, ".pending_upload").isFile }
                .sortedBy { it.id }
            notifyQueueChanged(appCtx)
            if (pending.isEmpty()) return@execute
            log.i("draining ${pending.size} pending capture(s)")
            onDrainState?.let { emitState(it, UploadState.Preparing) }
            for (saved in pending) {
                try {
                    val ref = uploadCaptureSync(appCtx, saved) { st ->
                        onDrainState?.let { emitState(it, st) }
                    }
                    clearCapturePending(appCtx, saved)
                    onDrainState?.let { emitState(it, UploadState.Success(ref)) }
                } catch (t: Throwable) {
                    val st = classifyFailure(t, allowQueue = true)
                    onDrainState?.let { emitState(it, st) }
                    log.w("drain stopped on ${saved.id}: ${t.message}")
                    break
                }
            }
            notifyQueueChanged(appCtx)
        }
    }

    internal fun buildMultipartEnvelope(
        captureId: String,
        boundary: String,
        jsonBytes: ByteArray,
        pngLength: Long,
    ): Triple<ByteArray, ByteArray, Long> =
        UploadPolicy.buildMultipartEnvelope(captureId, boundary, jsonBytes, pngLength)

    private fun uploadCaptureSync(
        ctx: Context,
        saved: CarPlayFrameGrabber.SavedCapture,
        onState: (UploadState) -> Unit,
    ): String {
        val base = baseUrl(ctx)
        val endpoint = "$base/api/captures"
        val jsonBytes = saved.jsonFile.readBytes()
        val pngLen = saved.pngFile.length()
        val boundary = "----GmCcpaCaptureBoundary${System.currentTimeMillis()}"
        val (prefix, suffix, totalBytes) = buildMultipartEnvelope(saved.id, boundary, jsonBytes, pngLen)

        return executeWithOneRetry {
            emitState(onState, UploadState.InProgress(0L, totalBytes))
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = WRITE_READ_TIMEOUT_MS
                doOutput = true
                setFixedLengthStreamingMode(totalBytes)
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("Accept", "application/json")
            }
            try {
                CountingRequestOutputStream(
                    delegate = conn.outputStream,
                    bytesTotal = totalBytes,
                ) { sent, tot ->
                    emitState(onState, UploadState.InProgress(sent, tot))
                }.use { out ->
                    out.write(prefix)
                    saved.pngFile.inputStream().use { input ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val r = input.read(buf)
                            if (r <= 0) break
                            out.write(buf, 0, r)
                        }
                    }
                    out.write(suffix)
                    out.flush()
                }
                emitState(onState, UploadState.Finalizing)
                val code = conn.responseCode
                val respBody = runCatching {
                    (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.readText()
                }.getOrNull()
                if (code !in 200..299) {
                    throw UploadPolicy.HttpStatusException(code, "HTTP $code ${respBody?.take(120) ?: ""}".trim())
                }
                log.i("uploaded capture ${saved.id} ($totalBytes B) to $endpoint")
                saved.id
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Kept so existing throw sites read unchanged; new code should use [UploadPolicy] directly. */
    class HttpStatusException(override val code: Int, message: String) : UploadPolicy.HttpStatusException(code, message)

    fun isTransientNetworkFailure(t: Throwable): Boolean =
        UploadPolicy.isTransientNetworkFailure(t)

    fun classifyFailure(t: Throwable, allowQueue: Boolean): UploadState =
        UploadPolicy.classifyFailure(t, allowQueue)

    fun <T> executeWithOneRetry(block: () -> T): T =
        UploadPolicy.executeWithOneRetry(
            onRetry = { first ->
                log.w("transient upload failure (${first.javaClass.simpleName}: ${first.message}) — retrying once after ${UploadPolicy.RETRY_BACKOFF_MS}ms")
            },
            block = block,
        )

    fun emitState(cb: (UploadState) -> Unit, state: UploadState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cb(state)
        } else {
            mainHandler.post { cb(state) }
        }
    }

    /**
     * Dialog to configure Base URL and preview/demo every [UploadState] without a car (Phase 2.3 & 2.4).
     */
    fun showUploadSettingsAndDemoDialog(act: Activity, onUrlSaved: ((String) -> Unit)? = null) {
        val dialog = Dialog(act)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.65f)

        val body = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = EqTheme.rounded(0xFF111826.toInt(), 28f, EqTheme.CARD_LINE)
            setPadding(EqTheme.px(36f), EqTheme.px(30f), EqTheme.px(36f), EqTheme.px(28f))
        }

        body.addView(EqTheme.text(act, 26f, EqTheme.TEXT, EqTheme.Weight.LIGHT).apply {
            text = "Upload Server & State Demo"
        })

        body.addView(EqTheme.text(act, 14f, EqTheme.ACCENT, EqTheme.Weight.MEDIUM).apply {
            text = "SERVER BASE URL"
            letterSpacing = 0.12f
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(16f) })

        val urlInput = EditText(act).apply {
            setText(baseUrl(act))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, EqTheme.sp(20f))
            setTextColor(EqTheme.TEXT)
            setHintTextColor(EqTheme.TEXT_FAINT)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            background = EqTheme.rounded(0x14FFFFFF, 14f, EqTheme.CARD_LINE)
            setPadding(EqTheme.px(16f), EqTheme.px(12f), EqTheme.px(16f), EqTheme.px(12f))
        }
        body.addView(urlInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(8f) })

        body.addView(EqTheme.text(act, 14f, EqTheme.ACCENT, EqTheme.Weight.MEDIUM).apply {
            text = "UPLOADACTIONBUTTON PREVIEW / DEMO (NO CAR NEEDED)"
            letterSpacing = 0.12f
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(22f) })

        val stateCaption = EqTheme.text(act, 16f, EqTheme.TEXT_DIM).apply {
            text = "Current state: Idle — tap the button or pick a state below"
        }
        body.addView(stateCaption, LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(6f) })

        val handler = Handler(Looper.getMainLooper())
        val demoRunnables = mutableListOf<Runnable>()
        fun clearDemoTimers() {
            demoRunnables.forEach { handler.removeCallbacks(it) }
            demoRunnables.clear()
        }

        lateinit var demoBtn: UploadActionButton
        fun applyDemoState(st: UploadState) {
            demoBtn.setState(st)
            stateCaption.text = "Current state: $st"
        }

        fun runFakeFlow(succeed: Boolean) {
            clearDemoTimers()
            val total = 512L * 1024L
            val steps = listOf<Pair<Long, UploadState>>(
                0L to UploadState.Preparing,
                400L to UploadState.InProgress(64L * 1024L, total),
                750L to UploadState.InProgress(240L * 1024L, total),
                1100L to UploadState.InProgress(410L * 1024L, total),
                1450L to UploadState.InProgress(512L * 1024L, total),
                1750L to UploadState.Finalizing,
                2150L to if (succeed) UploadState.Success("demo-20261014")
                else UploadState.Failed("HTTP 503 Service Busy", retryable = true),
            )
            for ((delayMs, st) in steps) {
                val r = Runnable { applyDemoState(st) }
                demoRunnables += r
                handler.postDelayed(r, delayMs)
            }
        }

        demoBtn = UploadActionButton(
            ctx = act,
            defaultLabel = "Demo Upload Action",
            defaultIcon = UploadIcon.UPLOAD,
            announceToast = false,
            onStatusAnnounce = { msg -> stateCaption.text = "Announced: $msg" },
            onTrigger = { runFakeFlow(succeed = true) },
        ).apply {
            onRetry = { runFakeFlow(succeed = true) }
        }
        body.addView(demoBtn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = EqTheme.px(12f) })

        val row1 = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        val row2 = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        fun chip(label: String, action: () -> Unit) = eqButton(act, label, EqButtonStyle.SECONDARY) {
            clearDemoTimers()
            action()
        }.apply {
            minWidth = EqTheme.px(120f)
            minHeight = EqTheme.px(48f)
            setPadding(EqTheme.px(18f), 0, EqTheme.px(18f), 0)
        }

        val total = 1000L * 1024L
        row1.addView(chip("Idle") { applyDemoState(UploadState.Idle) })
        row1.addView(chip("Preparing") { applyDemoState(UploadState.Preparing) },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = EqTheme.px(8f) })
        row1.addView(chip("47%") { applyDemoState(UploadState.InProgress(470L * 1024L, total)) },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = EqTheme.px(8f) })
        row1.addView(chip("Indeterm.") { applyDemoState(UploadState.InProgress(192L * 1024L, null)) },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = EqTheme.px(8f) })

        row2.addView(chip("Finalizing") { applyDemoState(UploadState.Finalizing) })
        row2.addView(chip("Success") { applyDemoState(UploadState.Success("ref-ok")) },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = EqTheme.px(8f) })
        row2.addView(chip("Queued") { applyDemoState(UploadState.Queued("offline")) },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = EqTheme.px(8f) })
        row2.addView(chip("Failed") { applyDemoState(UploadState.Failed("Timeout", retryable = true)) },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = EqTheme.px(8f) })

        body.addView(row1, LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(14f) })
        body.addView(row2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(8f) })

        val footer = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        footer.addView(eqButton(act, "Run Fail Flow", EqButtonStyle.SECONDARY) {
            runFakeFlow(succeed = false)
        })
        footer.addView(eqButton(act, "Close", EqButtonStyle.SECONDARY) {
            clearDemoTimers()
            dialog.dismiss()
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = EqTheme.px(12f) })
        footer.addView(eqButton(act, "Save URL", EqButtonStyle.PRIMARY) {
            clearDemoTimers()
            val newUrl = urlInput.text.toString().trim()
            setBaseUrl(act, newUrl)
            onUrlSaved?.invoke(baseUrl(act))
            dialog.dismiss()
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = EqTheme.px(12f) })

        body.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(24f) })
        dialog.setOnDismissListener { clearDemoTimers() }
        dialog.setContentView(body, ViewGroup.LayoutParams(EqTheme.px(880f), ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.show()
    }
}
