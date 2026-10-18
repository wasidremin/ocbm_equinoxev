package wasidremin.gmccpa.av

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Large movable in-call overlay pill: status label plus Answer / Hang up / Mute buttons.
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
    private val answerBtn = Button(context)
    private val endBtn = Button(context)
    private val muteBtn = Button(context)
    private var dragDx = 0f
    private var dragDy = 0f
    private var moved = false

    init {
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.cornerRadius = 48f
        bg.setColor(0xE6222B3A.toInt())
        bg.setStroke(3, 0xFF5AC8FA.toInt())
        background = bg
        elevation = 24f

        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val pad = dp(16)
        row.setPadding(pad, dp(12), pad, dp(12))
        label.textSize = 20f
        label.setTypeface(Typeface.DEFAULT_BOLD)
        label.setTextColor(0xFFFFFFFF.toInt())
        val labelLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        labelLp.marginEnd = dp(12)
        row.addView(label, labelLp)

        answerBtn.text = "Answer"
        answerBtn.textSize = 18f
        answerBtn.contentDescription = "Answer call"
        answerBtn.minimumHeight = dp(56)
        row.addView(answerBtn)
        endBtn.text = "Hang up"
        endBtn.textSize = 18f
        endBtn.contentDescription = "Hang up call"
        endBtn.minimumHeight = dp(56)
        val endLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        endLp.marginStart = dp(8)
        endLp.marginEnd = dp(8)
        row.addView(endBtn, endLp)
        muteBtn.textSize = 18f
        muteBtn.contentDescription = "Mute or unmute microphone"
        muteBtn.minimumHeight = dp(56)
        row.addView(muteBtn)

        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
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
        label.text = if (phase == CallPhase.RINGING) "Incoming call" else "On call"
        answerBtn.visibility = if (phase == CallPhase.RINGING) View.VISIBLE else View.GONE
        muteBtn.text = if (muted) "Unmute" else "Mute"
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
