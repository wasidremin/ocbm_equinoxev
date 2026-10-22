package wasidremin.gmccpa.av

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Large movable in-call overlay pill: status label plus Answer / Hang up / Mute actions.
 *
 * Driving-sized on purpose: every action is a 112dp touch target (icon disc + text label),
 * well above the ~76dp AAOS driving minimum, with 16dp gaps so a glove lands on one thing.
 * Each action is an icon disc plus a word — green handset to answer, red handset to hang up,
 * mic / crossed-mic to mute — drawn in code (framework-only, no assets) so it reads at arm's
 * length next to the CarPlay picture.
 *
 * The pill is shown while [CallPhase] is RINGING or ACTIVE and hidden when the call ends
 * ([CallPhase.NONE]) — the owner ([CarPlayActivity]) drives that via [bind]. Dragging anywhere
 * on the pill background (not on a button) repositions it inside its parent; the position is
 * persisted as parent-relative fractions in SharedPreferences so it survives rotation and
 * process restarts.
 *
 * Position and clamp math lives in the pure top-level [clampPill] / [fractionsToMargins]
 * helpers so it is JVM-testable; this class is the thin Android shell around them.
 */
class CallPillView(context: Context) : FrameLayout(context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val label = TextView(context)
    private val sublabel = TextView(context)
    private lateinit var answerBtn: PillActionButton
    private lateinit var endBtn: PillActionButton
    private lateinit var muteBtn: PillActionButton
    private var dragDx = 0f
    private var dragDy = 0f
    private var moved = false

    init {
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.cornerRadius = dp(36).toFloat()
        // CarPlay's call card: near-black glass with a faint edge, not an accent-coloured frame.
        bg.setColor(0xF01C1C1E.toInt())
        bg.setStroke(dp(1), 0x33FFFFFF)
        background = bg
        elevation = dp(24).toFloat()

        val col = LinearLayout(context)
        col.orientation = LinearLayout.VERTICAL
        val padH = dp(PAD_H_DP)
        col.setPadding(padH, dp(PAD_V_DP), padH, dp(PAD_V_DP))

        label.textSize = 26f
        label.setTypeface(Typeface.DEFAULT_BOLD)
        label.setTextColor(0xFFFFFFFF.toInt())
        label.gravity = Gravity.CENTER_HORIZONTAL
        col.addView(label, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        sublabel.textSize = 16f
        sublabel.setTextColor(0xFF8E8E93.toInt())
        sublabel.gravity = Gravity.CENTER_HORIZONTAL
        col.addView(sublabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
            bottomMargin = dp(12)
        })

        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_HORIZONTAL

        answerBtn = PillActionButton(context, PillIcon.ANSWER, "Answer", ACTION_GREEN)
        endBtn = PillActionButton(context, PillIcon.END, "Hang up", ACTION_RED)
        muteBtn = PillActionButton(context, PillIcon.MUTE, "Mute", ACTION_GREY)
        val btnLp = LinearLayout.LayoutParams(dp(TOUCH_DP), LinearLayout.LayoutParams.WRAP_CONTENT)
        row.addView(answerBtn, btnLp)
        row.addView(endBtn, LinearLayout.LayoutParams(dp(TOUCH_DP), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(GAP_DP); marginEnd = dp(GAP_DP)
        })
        row.addView(muteBtn, btnLp)
        col.addView(row)

        addView(col, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        visibility = View.GONE
        isClickable = true
        isFocusable = true

        // Drag on the background: button touches are consumed by the buttons themselves, so a
        // move reaching here started on empty pill space.
        setOnTouchListener { v, e ->
            val parent = parent as? android.view.ViewGroup ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val lp = v.layoutParams as? LayoutParams ?: return@setOnTouchListener false
                    dragDx = e.rawX - lp.leftMargin
                    dragDy = e.rawY - lp.topMargin
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val lp = (v.layoutParams as? LayoutParams) ?: return@setOnTouchListener false
                    val (cx, cy) = clampPill(
                        e.rawX - dragDx, e.rawY - dragDy,
                        v.width, v.height, parent.width, parent.height,
                    )
                    if (cx != lp.leftMargin.toFloat() || cy != lp.topMargin.toFloat()) moved = true
                    lp.leftMargin = cx.roundToInt()
                    lp.topMargin = cy.roundToInt()
                    v.layoutParams = lp
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) {
                        val lp = v.layoutParams as? LayoutParams
                        if (lp != null && parent.width > 0 && parent.height > 0) {
                            prefs.edit()
                                .putFloat(KEY_FX, lp.leftMargin.toFloat() / parent.width)
                                .putFloat(KEY_FY, lp.topMargin.toFloat() / parent.height)
                                .apply()
                        }
                    }
                    moved = false
                    // Return false so a tap (no move) still produces a click ripple; the drag
                    // consumed the gesture only when it actually moved.
                    false
                }
                else -> false
            }
        }
    }

    /**
     * Shows, hides, and labels the pill. Answer is offered only while RINGING — answering an
     * already-active call is meaningless and the button would be a mis-tap target next to Hang up.
     */
    fun bind(
        phase: CallPhase,
        muted: Boolean,
        onAnswer: () -> Unit,
        onEnd: () -> Unit,
        onMuteToggle: () -> Unit,
    ) {
        if (phase == CallPhase.NONE) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE
        val ringing = phase == CallPhase.RINGING
        label.text = if (ringing) "Incoming call" else "On call"
        sublabel.text = if (ringing) "Tap Answer or Hang up" else if (muted) "Microphone muted" else "Tap Hang up to end"
        sublabel.visibility = View.VISIBLE
        answerBtn.visibility = if (ringing) View.VISIBLE else View.GONE
        muteBtn.setIcon(if (muted) PillIcon.UNMUTE else PillIcon.MUTE)
        muteBtn.setLabel(if (muted) "Unmute" else "Mute")
        answerBtn.setOnClickListener { onAnswer() }
        endBtn.setOnClickListener { onEnd() }
        muteBtn.setOnClickListener { onMuteToggle() }
        // Place at the persisted spot once the parent has a size; a fresh install starts
        // top-center, clear of the status strip and the media card.
        post {
            val parent = parent as? android.view.ViewGroup ?: return@post
            if (parent.width == 0 || parent.height == 0) return@post
            val lp = layoutParams as? LayoutParams ?: LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START,
            )
            lp.gravity = Gravity.TOP or Gravity.START
            val (mx, my) = fractionsToMargins(
                prefs.getFloat(KEY_FX, -1f), prefs.getFloat(KEY_FY, -1f),
                width, height, parent.width, parent.height,
            )
            lp.leftMargin = mx
            lp.topMargin = my
            layoutParams = lp
        }
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val PREFS = "gmccpa_call_pill"
        private const val KEY_FX = "fx"
        private const val KEY_FY = "fy"
        /** 112dp square touch target per action: glove-landable at arm's length. */
        internal const val TOUCH_DP = 112
        internal const val GAP_DP = 16
        internal const val PAD_H_DP = 28
        internal const val PAD_V_DP = 20
        // iOS system colours, as CarPlay's own call buttons use them.
        internal const val ACTION_GREEN = 0xFF30D158.toInt()
        internal const val ACTION_RED = 0xFFFF453A.toInt()
        internal const val ACTION_GREY = 0xFF48484A.toInt()
        /** An engaged toggle (muted) turns white with a dark glyph, like CarPlay's. */
        internal const val ACTION_ACTIVE = 0xFFFFFFFF.toInt()
        /** Disc diameter; the surrounding column is [TOUCH_DP]. */
        internal const val DISC_DP = 76
    }
}

/** Which glyph a [PillActionButton] disc draws. */
internal enum class PillIcon { ANSWER, END, MUTE, UNMUTE }

/**
 * One driving-sized call action: a coloured icon disc with a word under it.
 * The whole 112dp column is the touch target (disc + label), with a ripple.
 */
internal class PillActionButton(
    context: Context,
    private var icon: PillIcon,
    label: String,
    private val discColor: Int,
) : LinearLayout(context) {
    private val disc = DiscView(context)
    private val text = TextView(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        disc.setIcon(icon, discColor)
        val d = (CallPillView.DISC_DP * resources.displayMetrics.density).roundToInt()
        addView(disc, LayoutParams(d, d))
        text.text = label
        text.textSize = 17f
        text.setTextColor(0xFFC7C7CC.toInt())
        text.gravity = Gravity.CENTER_HORIZONTAL
        addView(text, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = (8 * resources.displayMetrics.density).roundToInt()
        })
        isClickable = true
        isFocusable = true
        contentDescription = label
        val ripple = RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            null,
            android.graphics.drawable.ColorDrawable(Color.WHITE),
        )
        background = ripple
        minimumWidth = (CallPillView.TOUCH_DP * resources.displayMetrics.density).roundToInt()
        minimumHeight = (CallPillView.TOUCH_DP * resources.displayMetrics.density).roundToInt()
    }

    fun setLabel(s: String) {
        text.text = s
        contentDescription = s
    }

    fun setIcon(i: PillIcon) {
        icon = i
        disc.setIcon(i, if (i == PillIcon.UNMUTE) CallPillView.ACTION_ACTIVE else discColor)
        disc.invalidate()
    }
}

/**
 * Circular disc with a solid CarPlay-style glyph, drawn in code — no assets. White glyph on a
 * coloured disc; the engaged mute toggle is the inverse (dark glyph on white).
 */
internal class DiscView(context: Context) : View(context) {
    private var icon: PillIcon = PillIcon.ANSWER
    private var color: Int = CallPillView.ACTION_GREEN
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()

    fun setIcon(i: PillIcon, c: Int) {
        icon = i
        color = c
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f
        fill.color = color
        c.drawCircle(cx, cy, r, fill)
        val glyph = if (color == CallPillView.ACTION_ACTIVE) 0xFF1C1C1E.toInt() else Color.WHITE
        when (icon) {
            PillIcon.ANSWER -> drawHandset(c, cx, cy, r, glyph, hangUp = false)
            PillIcon.END -> drawHandset(c, cx, cy, r, glyph, hangUp = true)
            PillIcon.MUTE -> drawMic(c, cx, cy, r, glyph, crossed = false)
            PillIcon.UNMUTE -> drawMic(c, cx, cy, r, glyph, crossed = true)
        }
    }

    /**
     * Solid handset (SF Symbols `phone.fill`). Hang-up is the same handset swung 135 degrees so
     * the earpiece and mouthpiece point down (`phone.down.fill`) — the shape every CarPlay and
     * iOS call screen uses, so the two buttons read as opposites at a glance.
     */
    private fun drawHandset(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, hangUp: Boolean) {
        fill.color = color
        c.save()
        c.translate(cx, cy)
        if (hangUp) c.rotate(135f)
        val k = r * 1.08f / 12f
        c.scale(k, k)
        c.translate(-12f, -12f)
        c.drawPath(wasidremin.gmccpa.BarGlyphs.PHONE, fill)
        c.restore()
    }

    /** Solid microphone (`mic.fill`); [crossed] adds the slash of `mic.slash.fill`, with a gap cut in the disc colour. */
    private fun drawMic(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, crossed: Boolean) {
        val u = r / 12f
        fill.color = color
        rect.set(cx - 3.2f * u, cy - 9.2f * u, cx + 3.2f * u, cy + 2.4f * u)
        c.drawRoundRect(rect, 3.2f * u, 3.2f * u, fill)
        stroke.color = color
        stroke.strokeWidth = 1.7f * u
        rect.set(cx - 6.4f * u, cy - 6.6f * u, cx + 6.4f * u, cy + 5.8f * u)
        c.drawArc(rect, 15f, 150f, false, stroke)
        c.drawLine(cx, cy + 6.2f * u, cx, cy + 9.4f * u, stroke)
        c.drawLine(cx - 3.2f * u, cy + 9.4f * u, cx + 3.2f * u, cy + 9.4f * u, stroke)
        if (crossed) {
            stroke.color = this.color
            stroke.strokeWidth = 4.2f * u
            c.drawLine(cx - 7.4f * u, cy - 8.2f * u, cx + 7.4f * u, cy + 8.2f * u, stroke)
            stroke.color = color
            stroke.strokeWidth = 1.9f * u
            c.drawLine(cx - 7.4f * u, cy - 8.2f * u, cx + 7.4f * u, cy + 8.2f * u, stroke)
        }
    }
}

/**
 * Clamps a pill's top-left corner so it stays fully inside its parent. Pure for unit tests.
 */
internal fun clampPill(
    x: Float, y: Float, pillW: Int, pillH: Int, parentW: Int, parentH: Int,
): Pair<Float, Float> {
    val cx = x.coerceIn(0f, (parentW - pillW).coerceAtLeast(0).toFloat())
    val cy = y.coerceIn(0f, (parentH - pillH).toFloat().coerceAtLeast(0f))
    return cx to cy
}

/**
 * Converts persisted fractions to margins. Negative fractions (never saved) mean "first run":
 * top-center with a 96 dp-equivalent offset scaled by parent height.
 */
internal fun fractionsToMargins(
    fx: Float, fy: Float, pillW: Int, pillH: Int, parentW: Int, parentH: Int,
): Pair<Int, Int> {
    if (fx < 0f || fy < 0f) {
        val top = (parentH * 0.12f).roundToInt()
        val (cx, cy) = clampPill(
            (parentW - pillW) / 2f, top.toFloat(), pillW, pillH, parentW, parentH,
        )
        return cx.roundToInt() to cy.roundToInt()
    }
    val (cx, cy) = clampPill(fx * parentW, fy * parentH, pillW, pillH, parentW, parentH)
    return cx.roundToInt() to cy.roundToInt()
}
