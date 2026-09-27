package wasidremin.gmccpa

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Building blocks for the settings screens, in the same visual language as [StartupAnimationView]:
 * a near-black blue gradient, one blue accent, a thin lit line under the active item, and white
 * type that gets dimmer as it gets less important.
 *
 * Framework-only (android.jar + kotlin-stdlib), built in code, no XML, no AndroidX.
 *
 * Layout rule the screens should follow: left-aligned rows inside rounded cards. A row is a title,
 * an optional one-line explanation under it, and one control on the right (a [EqSegmented], an
 * [EqToggle], a value with a chevron, or nothing). Buttons are for actions, not for choices.
 *
 * Sizes are in dp multiplied by [EqTheme.scale], so the screen reads the same at arm's length on
 * the 17.7" panel as it would on a smaller one. Call [EqTheme.init] once with the root's width
 * before building views, and rebuild if the width changes a lot.
 */
object EqTheme {
    const val BG_TOP = 0xFF0A0F18.toInt()
    const val BG_BOTTOM = 0xFF020306.toInt()
    const val RAIL = 0xFF070B12.toInt()
    const val CARD = 0x0FFFFFFF            // white at 6 %
    const val CARD_LINE = 0x1AFFFFFF       // white at 10 %
    const val DIVIDER = 0x14FFFFFF         // white at 8 %
    const val TEXT = 0xFFF2F4F7.toInt()
    const val TEXT_DIM = 0xFF98A2B3.toInt()
    const val TEXT_FAINT = 0xFF5B6576.toInt()
    const val ACCENT = 0xFF4C9BFF.toInt()  // the light-bar blue
    const val ACCENT_SOFT = 0x334C9BFF
    const val OK = 0xFF4CC38A.toInt()
    const val WARN = 0xFFFFB23F.toInt()
    const val DANGER = 0xFFFF6B61.toInt()

    /** 1.0 on a 1100 dp wide window, capped so the big panel does not become a billboard. */
    var scale = 1f
        private set
    private var density = 1f

    fun init(ctx: Context, rootWidthPx: Int) {
        density = ctx.resources.displayMetrics.density
        val wDp = if (rootWidthPx > 0) rootWidthPx / density else 1100f
        scale = (wDp / 1100f).coerceIn(0.85f, 1.35f)
    }

    fun px(dp: Float): Int = (dp * scale * density + 0.5f).toInt()
    fun pxf(dp: Float): Float = dp * scale * density
    fun sp(v: Float): Float = v * scale

    fun screenBackground() = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG_TOP, BG_BOTTOM),
    )

    fun rounded(fill: Int, radiusDp: Float, stroke: Int = Color.TRANSPARENT, strokeDp: Float = 1f) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = pxf(radiusDp)
            setColor(fill)
            if (stroke != Color.TRANSPARENT) setStroke(max(1, px(strokeDp)), stroke)
        }

    /** Pressed-state feedback for any tappable surface. */
    fun ripple(content: android.graphics.drawable.Drawable?, radiusDp: Float): RippleDrawable {
        val mask = rounded(Color.WHITE, radiusDp)
        return RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), content, mask)
    }

    fun text(ctx: Context, sizeSp: Float, color: Int, weight: Weight = Weight.REGULAR): TextView =
        TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(sizeSp))
            setTextColor(color)
            typeface = weight.face
            includeFontPadding = false
        }

    enum class Weight(val face: Typeface) {
        LIGHT(Typeface.create("sans-serif-light", Typeface.NORMAL)),
        REGULAR(Typeface.create("sans-serif", Typeface.NORMAL)),
        MEDIUM(Typeface.create("sans-serif-medium", Typeface.NORMAL)),
    }
}

// ---- page structure ------------------------------------------------------------------------------

/** Page heading: large light title with an optional one-line lead under it. */
fun eqPageTitle(ctx: Context, title: String, lead: String? = null): LinearLayout =
    LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(EqTheme.text(ctx, 34f, EqTheme.TEXT, EqTheme.Weight.LIGHT).apply { text = title })
        if (lead != null) {
            addView(EqTheme.text(ctx, 18f, EqTheme.TEXT_DIM).apply {
                text = lead
                setLineSpacing(0f, 1.15f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(10f) })
        }
        setPadding(0, 0, 0, EqTheme.px(24f))
    }

/** Small spaced caps label above a card. */
fun eqSectionLabel(ctx: Context, label: String): TextView =
    EqTheme.text(ctx, 14f, EqTheme.ACCENT, EqTheme.Weight.MEDIUM).apply {
        text = label.uppercase()
        letterSpacing = 0.16f
        setPadding(EqTheme.px(4f), EqTheme.px(28f), 0, EqTheme.px(12f))
    }

/**
 * Rounded card that stacks rows with hairline dividers between them.
 * Use [addRow] rather than addView so the dividers stay correct.
 */
class EqCard(ctx: Context) : LinearLayout(ctx) {
    init {
        orientation = VERTICAL
        background = EqTheme.rounded(EqTheme.CARD, 22f, EqTheme.CARD_LINE)
        clipToOutline = true
    }

    fun addRow(row: View): EqCard {
        if (childCount > 0) {
            addView(View(context).apply { setBackgroundColor(EqTheme.DIVIDER) },
                LayoutParams(-1, max(1, EqTheme.px(1f))).apply {
                    marginStart = EqTheme.px(28f)
                    marginEnd = EqTheme.px(28f)
                })
        }
        addView(row, LayoutParams(-1, -2))
        return this
    }
}

/**
 * One setting: title, optional subtitle, optional control on the right.
 * If [onClick] is set the whole row is the touch target and gets a ripple.
 */
class EqRow(
    ctx: Context,
    title: String,
    subtitle: String? = null,
    trailing: View? = null,
    titleColor: Int = EqTheme.TEXT,
    onClick: (() -> Unit)? = null,
) : LinearLayout(ctx) {

    val titleView: TextView = EqTheme.text(ctx, 22f, titleColor, EqTheme.Weight.REGULAR).apply { text = title }
    val subtitleView: TextView = EqTheme.text(ctx, 16f, EqTheme.TEXT_DIM).apply {
        text = subtitle ?: ""
        visibility = if (subtitle.isNullOrEmpty()) GONE else VISIBLE
        setLineSpacing(0f, 1.12f)
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = EqTheme.px(96f)
        setPadding(EqTheme.px(28f), EqTheme.px(18f), EqTheme.px(24f), EqTheme.px(18f))
        val texts = LinearLayout(ctx).apply {
            orientation = VERTICAL
            addView(titleView)
            addView(subtitleView, LayoutParams(-1, -2).apply { topMargin = EqTheme.px(6f) })
        }
        addView(texts, LayoutParams(0, -2, 1f).apply { marginEnd = EqTheme.px(24f) })
        if (trailing != null) addView(trailing, LayoutParams(-2, -2))
        if (onClick != null) {
            background = EqTheme.ripple(null, 0f)
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    fun setSubtitle(text: String?) {
        subtitleView.text = text ?: ""
        subtitleView.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
    }
}

/** Value text plus a chevron, for rows that open something. */
fun eqValueChevron(ctx: Context, value: String): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    addView(EqTheme.text(ctx, 18f, EqTheme.TEXT_DIM).apply { text = value })
    addView(EqChevron(ctx), LinearLayout.LayoutParams(EqTheme.px(28f), EqTheme.px(28f)).apply {
        marginStart = EqTheme.px(10f)
    })
}

class EqChevron(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = EqTheme.TEXT_FAINT
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        p.strokeWidth = s * 0.1f
        val x = width / 2f
        val y = height / 2f
        c.drawLine(x - s * 0.12f, y - s * 0.24f, x + s * 0.14f, y, p)
        c.drawLine(x + s * 0.14f, y, x - s * 0.12f, y + s * 0.24f, p)
    }
}

// ---- controls ------------------------------------------------------------------------------------

/**
 * Segmented choice (one of N). The thumb slides to the selection. Tapping a segment calls
 * [onSelect] with its index; the caller persists it. Use for every "pick one" setting.
 */
class EqSegmented(
    ctx: Context,
    private val options: List<String>,
    selected: Int,
    private val onSelect: (Int) -> Unit,
) : View(ctx) {

    var selected: Int = selected.coerceIn(0, options.lastIndex)
        private set
    private var thumbPos = this.selected.toFloat()
    private var anim: ValueAnimator? = null

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14FFFFFF }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = EqTheme.ACCENT }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = EqTheme.Weight.MEDIUM.face
    }
    private val rect = RectF()
    private val segW: Float get() = width / options.size.toFloat()

    init {
        isClickable = true
        contentDescription = options.getOrNull(this.selected)
    }

    /** Update from outside without firing [onSelect]. */
    fun setSelected(index: Int, animate: Boolean = true) {
        val i = index.coerceIn(0, options.lastIndex)
        if (i == selected && anim == null) return
        selected = i
        contentDescription = options[i]
        anim?.cancel()
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            thumbPos = i.toFloat()
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(thumbPos, i.toFloat()).apply {
            duration = 220
            addUpdateListener { thumbPos = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { anim = null }
            })
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        label.textSize = EqTheme.pxf(17f)
        val widest = options.maxOf { label.measureText(it) }
        val wantW = ((widest + EqTheme.pxf(44f)) * options.size).roundToInt()
        val wantH = EqTheme.px(60f)
        setMeasuredDimension(resolveSize(wantW, widthMeasureSpec), resolveSize(wantH, heightMeasureSpec))
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val r = h / 2f
        rect.set(0f, 0f, width.toFloat(), h)
        c.drawRoundRect(rect, r, r, track)
        val inset = EqTheme.pxf(5f)
        val left = thumbPos * segW + inset
        rect.set(left, inset, left + segW - inset * 2, h - inset)
        c.drawRoundRect(rect, r - inset, r - inset, thumb)
        val baseline = h / 2f - (label.descent() + label.ascent()) / 2f
        for (i in options.indices) {
            val nearness = 1f - min(1f, kotlin.math.abs(thumbPos - i))
            label.color = blend(EqTheme.TEXT_DIM, 0xFF06101E.toInt(), nearness)
            c.drawText(options[i], segW * i + segW / 2f, baseline, label)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            val i = (e.x / segW).toInt().coerceIn(0, options.lastIndex)
            if (i != selected) {
                setSelected(i)
                onSelect(i)
            }
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

/** On/off switch. [onChange] fires with the new value; the caller persists it. */
class EqToggle(
    ctx: Context,
    checked: Boolean,
    private val onChange: (Boolean) -> Unit,
) : View(ctx) {

    var checked: Boolean = checked
        private set
    private var knob = if (checked) 1f else 0f
    private var anim: ValueAnimator? = null
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()

    init {
        isClickable = true
        contentDescription = if (checked) "On" else "Off"
    }

    fun setChecked(value: Boolean, animate: Boolean = true) {
        if (value == checked) return
        checked = value
        contentDescription = if (value) "On" else "Off"
        anim?.cancel()
        val target = if (value) 1f else 0f
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) { knob = target; invalidate(); return }
        anim = ValueAnimator.ofFloat(knob, target).apply {
            duration = 180
            addUpdateListener { knob = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(EqTheme.px(84f), EqTheme.px(48f))
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        trackPaint.color = blend(0x33FFFFFF, EqTheme.ACCENT, knob)
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, h / 2f, h / 2f, trackPaint)
        val r = h / 2f - EqTheme.pxf(5f)
        val cx = h / 2f + (w - h) * knob
        c.drawCircle(cx, h / 2f, r, knobPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            setChecked(!checked)
            onChange(checked)
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

enum class EqButtonStyle { PRIMARY, SECONDARY, DANGER }

/** Capsule action button. PRIMARY is filled blue; SECONDARY is outlined; DANGER is red text on a faint fill. */
fun eqButton(ctx: Context, label: String, style: EqButtonStyle, onClick: () -> Unit): TextView =
    EqTheme.text(ctx, 19f, EqTheme.TEXT, EqTheme.Weight.MEDIUM).apply {
        text = label
        gravity = Gravity.CENTER
        minHeight = EqTheme.px(64f)
        minWidth = EqTheme.px(200f)
        setPadding(EqTheme.px(36f), 0, EqTheme.px(36f), 0)
        val radius = 32f
        val bg = when (style) {
            EqButtonStyle.PRIMARY -> EqTheme.rounded(EqTheme.ACCENT, radius)
            EqButtonStyle.SECONDARY -> EqTheme.rounded(Color.TRANSPARENT, radius, 0x55FFFFFF, 1.5f)
            EqButtonStyle.DANGER -> EqTheme.rounded(0x1AFF6B61, radius)
        }
        setTextColor(when (style) {
            EqButtonStyle.PRIMARY -> 0xFF06101E.toInt()
            EqButtonStyle.SECONDARY -> EqTheme.TEXT
            EqButtonStyle.DANGER -> EqTheme.DANGER
        })
        background = EqTheme.ripple(bg, radius)
        isClickable = true
        setOnClickListener { onClick() }
    }

// ---- navigation and status -----------------------------------------------------------------------

/**
 * Left navigation. Items are text; the selected one is white with a short lit line beside it,
 * the same blue as the startup light bar. [footer] sits at the bottom (version text).
 */
class EqNavRail(
    ctx: Context,
    private val items: List<String>,
    private val onSelect: (Int) -> Unit,
    footer: String? = null,
) : LinearLayout(ctx) {

    private val rows = mutableListOf<TextView>()
    private val bars = mutableListOf<View>()
    var selected = 0
        private set

    init {
        orientation = VERTICAL
        setBackgroundColor(EqTheme.RAIL)
        setPadding(0, EqTheme.px(36f), 0, EqTheme.px(24f))
        items.forEachIndexed { i, name ->
            val row = FrameLayout(ctx)
            val bar = View(ctx).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.TRANSPARENT, EqTheme.ACCENT, Color.TRANSPARENT),
                )
            }
            row.addView(bar, FrameLayout.LayoutParams(EqTheme.px(4f), EqTheme.px(44f), Gravity.CENTER_VERTICAL))
            val label = EqTheme.text(ctx, 21f, EqTheme.TEXT_DIM, EqTheme.Weight.REGULAR).apply {
                text = name
                gravity = Gravity.CENTER_VERTICAL
                setPadding(EqTheme.px(32f), 0, EqTheme.px(16f), 0)
            }
            row.addView(label, FrameLayout.LayoutParams(-1, EqTheme.px(76f)))
            row.background = EqTheme.ripple(null, 0f)
            row.isClickable = true
            row.setOnClickListener { select(i); onSelect(i) }
            addView(row, LayoutParams(-1, -2))
            rows += label
            bars += bar
        }
        addView(View(ctx), LayoutParams(0, 0, 1f))
        if (footer != null) {
            addView(EqTheme.text(ctx, 14f, EqTheme.TEXT_FAINT).apply {
                text = footer
                setPadding(EqTheme.px(32f), 0, EqTheme.px(16f), 0)
            })
        }
        select(0)
    }

    fun select(index: Int) {
        selected = index
        rows.forEachIndexed { i, t ->
            val on = i == index
            t.setTextColor(if (on) EqTheme.TEXT else EqTheme.TEXT_DIM)
            t.typeface = (if (on) EqTheme.Weight.MEDIUM else EqTheme.Weight.REGULAR).face
            bars[i].visibility = if (on) VISIBLE else INVISIBLE
        }
    }
}

/**
 * The connection readout at the top of every settings page: a coloured dot, the state in plain
 * words, one line of detail, and an optional action on the right (for example "Back to CarPlay").
 * Colours: ACCENT working, OK live, WARN attention, DANGER failed, TEXT_FAINT idle.
 */
class EqStatusStrip(ctx: Context) : LinearLayout(ctx) {
    private val dot = View(ctx)
    private val title = EqTheme.text(ctx, 22f, EqTheme.TEXT, EqTheme.Weight.MEDIUM)
    private val detail = EqTheme.text(ctx, 16f, EqTheme.TEXT_DIM).apply {
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val actionSlot = FrameLayout(ctx)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = EqTheme.rounded(EqTheme.CARD, 22f, EqTheme.CARD_LINE)
        setPadding(EqTheme.px(28f), EqTheme.px(20f), EqTheme.px(20f), EqTheme.px(20f))
        val d = EqTheme.px(14f)
        addView(dot, LayoutParams(d, d).apply { marginEnd = EqTheme.px(20f) })
        val texts = LinearLayout(ctx).apply {
            orientation = VERTICAL
            addView(title)
            addView(detail, LayoutParams(-1, -2).apply { topMargin = EqTheme.px(6f) })
        }
        addView(texts, LayoutParams(0, -2, 1f))
        addView(actionSlot, LayoutParams(-2, -2))
        set("Not connected", null, EqTheme.TEXT_FAINT)
    }

    fun set(state: String, detailText: String?, color: Int) {
        title.text = state
        detail.text = detailText ?: ""
        detail.visibility = if (detailText.isNullOrEmpty()) GONE else VISIBLE
        dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    }

    fun setAction(view: View?) {
        actionSlot.removeAllViews()
        if (view != null) actionSlot.addView(view)
    }
}

// ---- dialogs -------------------------------------------------------------------------------------

/**
 * Confirmation sheet in the same style (no white system dialog on a dark dash).
 * [destructive] colours the confirm button red.
 */
object EqDialog {
    fun confirm(
        act: Activity,
        title: String,
        message: String,
        confirmLabel: String,
        destructive: Boolean,
        onConfirm: () -> Unit,
    ) {
        val dialog = Dialog(act)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.6f)
        val body = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = EqTheme.rounded(0xFF111826.toInt(), 28f, EqTheme.CARD_LINE)
            setPadding(EqTheme.px(40f), EqTheme.px(36f), EqTheme.px(40f), EqTheme.px(28f))
            addView(EqTheme.text(act, 28f, EqTheme.TEXT, EqTheme.Weight.LIGHT).apply { text = title })
            addView(EqTheme.text(act, 18f, EqTheme.TEXT_DIM).apply {
                text = message
                setLineSpacing(0f, 1.15f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(14f) })
            val buttons = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            buttons.addView(eqButton(act, "Cancel", EqButtonStyle.SECONDARY) { dialog.dismiss() })
            buttons.addView(
                eqButton(act, confirmLabel, if (destructive) EqButtonStyle.DANGER else EqButtonStyle.PRIMARY) {
                    dialog.dismiss()
                    onConfirm()
                },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = EqTheme.px(16f) },
            )
            addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(32f) })
        }
        dialog.setContentView(body, ViewGroup.LayoutParams(EqTheme.px(720f), ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.show()
    }
}

// ---- helpers -------------------------------------------------------------------------------------

internal fun blend(a: Int, b: Int, t: Float): Int {
    val u = t.coerceIn(0f, 1f)
    fun ch(s: Int): Int {
        val x = (a ushr s) and 0xFF
        val y = (b ushr s) and 0xFF
        return (x + (y - x) * u).roundToInt().coerceIn(0, 255)
    }
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** Faint lit line under a page title, matching the startup bar. Height 2 dp, fades at both ends. */
class EqLightLine(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        p.shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
            intArrayOf(EqTheme.ACCENT, 0x664C9BFF, Color.TRANSPARENT), floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP)
    }
    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
    }
}
