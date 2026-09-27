package wasidremin.gmccpa

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Full-screen startup animation shown while the adapter, the iPhone and CarPlay come up.
 *
 * The motif is the Equinox EV's front light bar: on start the bar lights from the centre out,
 * like the car's welcome lighting. While the app waits, light pulses travel from the centre to
 * both ends. The pulses speed up as each connection step completes. When CarPlay's first video
 * frame is on screen, [finish] stretches the bar to full width and fades the view out to reveal
 * the CarPlay picture underneath.
 *
 * Framework-only (android.jar + kotlin-stdlib). No AndroidX, no XML, no bitmaps. All paints and
 * shaders are allocated up front or when the size or accent colour changes, never per frame.
 *
 * Usage:
 *   val intro = StartupAnimationView(context)
 *   root.addView(intro, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
 *   intro.setStage(StartupAnimationView.stageFor(linkState), StartupAnimationView.statusFor(linkState))
 *   intro.setPhoneName("Justin’s iPhone 17 pro")
 *   intro.finish()   // at the first rendered CarPlay frame
 *
 * The view is clickable, so taps don't reach whatever is underneath while it shows. Attach a
 * long-click listener if you want a way through to the diagnostic screen.
 */
class StartupAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** What the driver is waiting on. READY is reached through [finish]. */
    enum class Stage { ADAPTER, PHONE, CARPLAY, READY, FAILED }

    /** Wordmark above the light bar. */
    var title: String = "EQUINOX EV"
        set(value) { field = value; invalidate() }

    /** Runs once, on the main thread, after [finish] has faded the view out and set it GONE. */
    var onFinished: (() -> Unit)? = null

    // ---- state -------------------------------------------------------------------------------

    private var stage = Stage.ADAPTER
    /** Index of the step shown as active: 0 adapter, 1 iPhone, 2 CarPlay, 3 all done. */
    private var activeStep = 0
    private var phoneName: String? = null
    private var statusOverride: String? = null
    private var detail: String? = null

    private var shownStatus = ""
    private var previousStatus = ""
    private var statusChangedAt = 0L

    private var introStart = 0L
    private var introSkipped = false
    private var sweepStart = 0L
    private var finishStart = 0L
    private var lastFrame = 0L
    private var failMix = 0f
    private var running = false
    private var finished = false

    private val motion: Boolean get() = ValueAnimator.areAnimatorsEnabled()

    // ---- geometry (set in onSizeChanged) -----------------------------------------------------

    private var cx = 0f
    private var cy = 0f
    private var halfBar = 1f
    private var barThick = 1f
    private var glowHalfHeight = 1f
    private var cometLen = 1f
    private var dotR = 1f

    // ---- paints and shaders ------------------------------------------------------------------

    private val bgPaint = Paint()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cometPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = textPaint("sans-serif-light", Typeface.NORMAL, 0.42f)
    private val statusPaint = textPaint("sans-serif", Typeface.NORMAL, 0.02f)
    private val detailPaint = textPaint("sans-serif", Typeface.NORMAL, 0.02f)
    private val labelPaint = textPaint("sans-serif-medium", Typeface.NORMAL, 0.16f)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var barShader: LinearGradient? = null
    private var cometShader: LinearGradient? = null
    private var glowShader: RadialGradient? = null
    private var shaderAccent = 0
    private val shaderMatrix = Matrix()
    private val rect = RectF()

    init {
        isClickable = true
        isFocusable = false
        updateStatusText()
    }

    // ---- public API --------------------------------------------------------------------------

    /**
     * Move to [next]. [status] replaces the default line for that stage. [detail] is a smaller
     * second line, meant for FAILED. READY is the same as calling [finish].
     * Ignored once [finish] has started.
     */
    fun setStage(next: Stage, status: String? = null, detail: String? = null) {
        if (finishStart != 0L || finished) return
        if (next == Stage.READY) { finish(); return }
        if (next != stage) {
            if (next != Stage.FAILED) activeStep = stepOf(next)
            stage = next
            sweepStart = SystemClock.uptimeMillis()
        }
        statusOverride = status
        this.detail = detail
        updateStatusText()
        invalidate()
    }

    /** Name shown in "Connecting to …". Pass the last known name early so it shows from the start. */
    fun setPhoneName(name: String?) {
        phoneName = name?.trim()?.takeIf { it.isNotEmpty() }
        updateStatusText()
        invalidate()
    }

    /** Start with the bar already lit. Use when this view continues an animation from another screen. */
    fun skipIntro() {
        introSkipped = true
        invalidate()
    }

    /** Stretch the bar to full width, then fade out and go GONE. Safe to call more than once. */
    fun finish() {
        if (finishStart != 0L || finished) return
        finishStart = SystemClock.uptimeMillis()
        stage = Stage.READY
        activeStep = 3
        statusOverride = null
        detail = null
        updateStatusText()
        val m = motion
        animate().cancel()
        animate()
            .alpha(0f)
            .setStartDelay(if (m) (FINISH_MS * 0.4f).toLong() else 0L)
            .setDuration(if (m) (FINISH_MS * 0.6f).toLong() else 0L)
            .withEndAction {
                finished = true
                running = false
                visibility = GONE
                val done = onFinished
                onFinished = null
                done?.invoke()
            }
            .start()
        invalidate()
    }

    /** Back to the first frame of the intro, visible. Use when a session ends and the wait starts again. */
    fun reset() {
        animate().cancel()
        alpha = 1f
        visibility = VISIBLE
        finished = false
        finishStart = 0L
        introStart = 0L
        introSkipped = false
        lastFrame = 0L
        stage = Stage.ADAPTER
        activeStep = 0
        failMix = 0f
        statusOverride = null
        detail = null
        previousStatus = ""
        shownStatus = ""
        updateStatusText()
        running = isAttachedToWindow
        invalidate()
    }

    // ---- lifecycle ---------------------------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = !finished
        lastFrame = 0L
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        running = isVisible && isAttachedToWindow && !finished
        if (running) {
            lastFrame = 0L
            postInvalidateOnAnimation()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val fw = w.toFloat()
        val fh = h.toFloat()
        val density = resources.displayMetrics.density
        cx = fw / 2f
        cy = fh * 0.42f
        halfBar = max(1f, min(fw * 0.33f, fh * 1.05f))
        barThick = max(2f * density, fh * 0.0065f)
        glowHalfHeight = fh * 0.11f
        cometLen = halfBar * 0.22f
        dotR = max(3f * density, fh * 0.0095f)

        bgPaint.shader = LinearGradient(0f, 0f, 0f, fh, BG_TOP, BG_BOTTOM, Shader.TileMode.CLAMP)
        titlePaint.textSize = fh * 0.046f
        statusPaint.textSize = fh * 0.034f
        detailPaint.textSize = fh * 0.024f
        labelPaint.textSize = fh * 0.020f
        ringPaint.strokeWidth = max(1.5f * density, fh * 0.003f)
        linePaint.strokeWidth = max(1f * density, fh * 0.0022f)
        shaderAccent = 0   // geometry changed: rebuild the shaders on the next frame
    }

    // ---- drawing -----------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (introStart == 0L) {
            introStart = now
            sweepStart = now
        }
        val dt = if (lastFrame == 0L) 16L else (now - lastFrame).coerceIn(0L, 100L)
        lastFrame = now
        val animate = motion

        // Accent eases from blue to amber on FAILED, and back.
        val failTarget = if (stage == Stage.FAILED) 1f else 0f
        failMix = if (!animate) failTarget else failMix + (failTarget - failMix) * min(1f, dt / 220f)
        if (abs(failTarget - failMix) < 0.004f) failMix = failTarget
        val accent = lerpColor(ACCENT, FAIL_ACCENT, (failMix * 32f).roundToInt() / 32f)
        ensureShaders(accent)

        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val intro = if (!animate || introSkipped) 1f
        else easeOutCubic(((now - introStart) / INTRO_MS.toFloat()).coerceIn(0f, 1f))
        val fin = if (finishStart == 0L) 0f
        else if (!animate) 1f
        else ((now - finishStart) / FINISH_MS.toFloat()).coerceIn(0f, 1f)
        val finE = easeInOutCubic(fin)
        val breathe = if (animate) 0.5f + 0.5f * sin(2.0 * PI * (now % BREATH_MS) / BREATH_MS).toFloat() else 0.5f

        drawGlow(canvas, intro, finE, breathe)
        val thick = drawBar(canvas, intro, finE, w)
        if (animate && fin == 0f && intro >= 1f && stage != Stage.FAILED) drawComets(canvas, now, thick)

        val textIn = if (!animate || introSkipped) 1f
        else ((now - introStart - 300L) / 600f).coerceIn(0f, 1f)
        val textAlpha = textIn * (1f - (fin * 2.2f).coerceAtMost(1f))
        if (textAlpha > 0f) {
            drawText(canvas, now, h, textAlpha, accent, animate)
            drawSteps(canvas, now, h, w, textAlpha, accent, animate)
        }

        if (running && !finished && (animate || finishStart != 0L)) postInvalidateOnAnimation()
    }

    private fun drawGlow(canvas: Canvas, intro: Float, finE: Float, breathe: Float) {
        val shader = glowShader ?: return
        val strength = ((0.55f + 0.30f * breathe) * intro + 0.6f * finE).coerceIn(0f, 1f)
        val gw = halfBar * (0.6f + 0.4f * intro) * (1f + 1.2f * finE)
        val gh = glowHalfHeight * (1f + 2f * finE)
        shaderMatrix.setScale(gw / halfBar, gh / halfBar, cx, cy)
        shader.setLocalMatrix(shaderMatrix)
        glowPaint.alpha = (255 * strength).toInt()
        rect.set(cx - gw, cy - gh, cx + gw, cy + gh)
        canvas.drawOval(rect, glowPaint)
    }

    /** Returns the bar thickness drawn, for the comets. */
    private fun drawBar(canvas: Canvas, intro: Float, finE: Float, w: Float): Float {
        val shader = barShader ?: return barThick
        val half = halfBar * intro + (w / 2f - halfBar * intro) * finE
        if (half <= 0.5f) return barThick
        shaderMatrix.setScale(half / halfBar, 1f, cx, 0f)
        shader.setLocalMatrix(shaderMatrix)
        val thick = barThick * (1f + 1.5f * finE)
        rect.set(cx - half, cy - thick / 2f, cx + half, cy + thick / 2f)
        barPaint.alpha = 255
        canvas.drawRoundRect(rect, thick / 2f, thick / 2f, barPaint)
        return thick
    }

    /** Two light pulses leave the centre and run to the ends. Faster at each step. */
    private fun drawComets(canvas: Canvas, now: Long, thick: Float) {
        val shader = cometShader ?: return
        val period = when (stage) {
            Stage.ADAPTER -> 2400L
            Stage.PHONE -> 1700L
            Stage.CARPLAY -> 1100L
            else -> 2400L
        }
        val ph = ((now - sweepStart) % period) / period.toFloat()
        val travel = easeInOutSine(ph)
        val head = halfBar * (0.08f + 0.88f * travel)
        val fade = sin(PI * ph).toFloat()
        cometPaint.alpha = (255 * fade).toInt()
        val ct = thick * 1.8f
        val top = cy - ct / 2f
        val bottom = cy + ct / 2f
        val r = ct / 2f

        val right = cx + head
        shaderMatrix.setTranslate(right - cometLen, 0f)
        shader.setLocalMatrix(shaderMatrix)
        rect.set(right - cometLen, top, right, bottom)
        canvas.drawRoundRect(rect, r, r, cometPaint)

        val left = cx - head
        shaderMatrix.setScale(-1f, 1f)
        shaderMatrix.postTranslate(left + cometLen, 0f)
        shader.setLocalMatrix(shaderMatrix)
        rect.set(left, top, left + cometLen, bottom)
        canvas.drawRoundRect(rect, r, r, cometPaint)
    }

    private fun drawText(canvas: Canvas, now: Long, h: Float, textAlpha: Float, accent: Int, animate: Boolean) {
        titlePaint.color = withAlpha(Color.WHITE, (235 * textAlpha).toInt())
        canvas.drawText(title, cx, cy - h * 0.095f, titlePaint)

        val statusY = cy + h * 0.125f
        val cf = if (!animate) 1f
        else ((now - statusChangedAt) / STATUS_FADE_MS.toFloat()).coerceIn(0f, 1f)
        val lift = h * 0.014f
        if (cf < 1f && previousStatus.isNotEmpty()) {
            statusPaint.color = withAlpha(Color.WHITE, (230 * (1f - cf) * textAlpha).toInt())
            canvas.drawText(previousStatus, cx, statusY - lift * cf, statusPaint)
        }
        statusPaint.color = withAlpha(Color.WHITE, (230 * cf * textAlpha).toInt())
        canvas.drawText(shownStatus, cx, statusY + lift * (1f - cf), statusPaint)

        val d = detail
        if (!d.isNullOrEmpty()) {
            val base = if (stage == Stage.FAILED) accent else Color.WHITE
            detailPaint.color = withAlpha(base, (150 * textAlpha).toInt())
            canvas.drawText(d, cx, cy + h * 0.182f, detailPaint)
        }
    }

    private fun drawSteps(canvas: Canvas, now: Long, h: Float, w: Float, textAlpha: Float, accent: Int, animate: Boolean) {
        val y = cy + h * 0.31f
        val gap = min(w * 0.14f, h * 0.30f)
        val x0 = cx - gap
        val failed = stage == Stage.FAILED
        val pulse = if (animate && !failed) (now % PULSE_MS) / PULSE_MS.toFloat() else 0f

        for (i in STEP_LABELS.indices) {
            val x = x0 + gap * i
            if (i < STEP_LABELS.lastIndex) {
                val done = activeStep > i
                linePaint.color = if (done) withAlpha(accent, (190 * textAlpha).toInt())
                else withAlpha(Color.WHITE, (38 * textAlpha).toInt())
                canvas.drawLine(x + dotR * 3f, y, x + gap - dotR * 3f, y, linePaint)
            }
            when {
                i < activeStep -> {
                    dotPaint.color = withAlpha(accent, (255 * textAlpha).toInt())
                    canvas.drawCircle(x, y, dotR, dotPaint)
                }
                i == activeStep -> {
                    dotPaint.color = withAlpha(accent, (255 * textAlpha).toInt())
                    canvas.drawCircle(x, y, dotR, dotPaint)
                    ringPaint.color = withAlpha(accent, ((if (failed) 170f else 200f * (1f - pulse)) * textAlpha).toInt())
                    canvas.drawCircle(x, y, dotR * (1.6f + (if (failed) 0.4f else 1.6f * pulse)), ringPaint)
                }
                else -> {
                    ringPaint.color = withAlpha(Color.WHITE, (70 * textAlpha).toInt())
                    canvas.drawCircle(x, y, dotR, ringPaint)
                }
            }
            labelPaint.color = withAlpha(Color.WHITE, ((if (i <= activeStep) 200 else 90) * textAlpha).toInt())
            canvas.drawText(STEP_LABELS[i], x, y + h * 0.058f, labelPaint)
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    private fun ensureShaders(accent: Int) {
        if (accent == shaderAccent && barShader != null) return
        shaderAccent = accent
        barShader = LinearGradient(
            cx - halfBar, 0f, cx + halfBar, 0f,
            intArrayOf(Color.TRANSPARENT, withAlpha(accent, 210), Color.WHITE, withAlpha(accent, 210), Color.TRANSPARENT),
            floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f),
            Shader.TileMode.CLAMP,
        ).also { barPaint.shader = it }
        cometShader = LinearGradient(
            0f, 0f, cometLen, 0f,
            intArrayOf(Color.TRANSPARENT, withAlpha(accent, 190), Color.WHITE),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP,
        ).also { cometPaint.shader = it }
        glowShader = RadialGradient(
            cx, cy, halfBar,
            intArrayOf(withAlpha(accent, 120), withAlpha(accent, 42), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        ).also { glowPaint.shader = it }
    }

    private fun updateStatusText() {
        val next = statusOverride ?: defaultStatus()
        if (next != shownStatus) {
            previousStatus = shownStatus
            shownStatus = next
            statusChangedAt = SystemClock.uptimeMillis()
            contentDescription = next
        }
    }

    private fun defaultStatus(): String = when (stage) {
        Stage.ADAPTER -> "Starting the adapter"
        Stage.PHONE -> phoneName?.let { "Connecting to $it" } ?: "Connecting to your iPhone"
        Stage.CARPLAY -> "Starting CarPlay"
        Stage.READY -> "Ready"
        Stage.FAILED -> "Can’t reach CarPlay"
    }

    private fun stepOf(s: Stage): Int = when (s) {
        Stage.ADAPTER -> 0
        Stage.PHONE -> 1
        Stage.CARPLAY -> 2
        Stage.READY -> 3
        Stage.FAILED -> activeStep
    }

    private fun textPaint(family: String, style: Int, spacing: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(family, style)
        letterSpacing = spacing
        color = Color.WHITE
    }

    companion object {
        /** Equinox-style UI blue. */
        private const val ACCENT = 0xFF4C9BFF.toInt()
        private const val FAIL_ACCENT = 0xFFFFB23F.toInt()
        private const val BG_TOP = 0xFF0A0F18.toInt()
        private const val BG_BOTTOM = 0xFF020306.toInt()

        private const val INTRO_MS = 1100L
        private const val FINISH_MS = 750L
        private const val BREATH_MS = 3600L
        private const val STATUS_FADE_MS = 280L
        private const val PULSE_MS = 1400L

        private val STEP_LABELS = arrayOf("ADAPTER", "IPHONE", "CARPLAY")

        /** Maps the launcher's LinkState (by enum name, so this file has no dependency on it). */
        fun stageFor(linkState: Enum<*>): Stage = when (linkState.name) {
            "WAITING", "PAIRING" -> Stage.PHONE
            "PHONE_DETECTED", "STARTING" -> Stage.CARPLAY
            "LIVE" -> Stage.READY
            "FAILED", "STALLED" -> Stage.FAILED
            else -> Stage.ADAPTER   // STOPPED, RESTARTING, SEARCHING, CLAIMING
        }

        /** Driver-facing line for a LinkState, or null to use the stage's default. */
        fun statusFor(linkState: Enum<*>): String? = when (linkState.name) {
            "STOPPED", "SEARCHING" -> "Waiting for the adapter"
            "RESTARTING" -> "Restarting"
            "WAITING" -> "Looking for your iPhone"
            "PHONE_DETECTED" -> "iPhone found"
            else -> null
        }

        private fun withAlpha(color: Int, alpha: Int): Int =
            (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

        private fun lerpColor(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int): Int {
                val x = (a shr shift) and 0xFF
                val y = (b shr shift) and 0xFF
                return (x + (y - x) * t).roundToInt().coerceIn(0, 255)
            }
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        private fun easeOutCubic(t: Float): Float = 1f - (1f - t).pow(3)
        private fun easeInOutCubic(t: Float): Float =
            if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f
        private fun easeInOutSine(t: Float): Float = (-(cos(PI * t) - 1.0) / 2.0).toFloat()
    }
}
