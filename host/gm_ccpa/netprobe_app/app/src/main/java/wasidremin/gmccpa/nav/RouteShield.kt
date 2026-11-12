package wasidremin.gmccpa.nav

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/**
 * The highway marker in front of a road name: "I-75 N" is drawn as the Interstate shield with 75
 * in it, followed by "N".
 *
 * The phone sends a road as text only, so the marker is read off the front of that text. Only a
 * leading designator is taken, and only forms that are unambiguous: `I-`, `US-`, a two-letter
 * state code, `M-` (Michigan), `K-` (Kansas), `SR`/`Hwy`/`Route`, and `CR`. Anything else is left
 * as the plain text it was.
 *
 * Shapes follow the MUTCD route signs (Figure 2D-3): M1-1 Interstate, M1-4 U.S. route, M1-5 the
 * default state route circle, M1-6 county pentagon. States with their own marker are drawn with
 * the default circle; [Kind.STATE] carries the state code so a per-state shape can be added here
 * without touching the callers.
 */
object RouteShield {
    enum class Kind { INTERSTATE, US, STATE, COUNTY }

    /** [number] as printed ("75", "9W"); [state] the postal code when the text named one. */
    data class Shield(val kind: Kind, val number: String, val state: String? = null)

    /** A road split into its marker and what follows it ("N", "S / Main St", or empty). */
    data class Parsed(val shield: Shield, val rest: String)

    private val STATES = setOf(
        "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "DC", "FL", "GA", "HI", "ID", "IL", "IN", "IA", "KS", "KY",
        "LA", "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH",
        "OK", "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY",
    )
    // Hyphen, non-breaking hyphen, figure dash, en dash: the phone's text uses more than one.
    private val ROUTE = Regex(
        "^\\s*(I|US|U\\.S\\.|[A-Z]{2}|M|K|SR|CR|Hwy|Highway|Route|Rte)([\\s\\-\\u2010\\u2011\\u2012\\u2013]+)(\\d{1,3}[A-Z]?)(?![\\w])\\s*(.*)$"
    )

    /** Null when [road] does not start with a route designator. */
    fun parse(road: String?): Parsed? {
        val m = ROUTE.find(road ?: return null) ?: return null
        val prefix = m.groupValues[1]
        val number = m.groupValues[3]
        val shield = when (prefix) {
            "I" -> Shield(Kind.INTERSTATE, number)
            "US", "U.S." -> Shield(Kind.US, number)
            "CR" -> Shield(Kind.COUNTY, number)
            "M" -> Shield(Kind.STATE, number, "MI")
            "K" -> Shield(Kind.STATE, number, "KS")
            "SR", "Hwy", "Highway", "Route", "Rte" -> Shield(Kind.STATE, number)
            else -> if (prefix in STATES) Shield(Kind.STATE, number, prefix) else return null
        }
        // "Route 66 Diner Rd" is a street, not a route: a bare word prefix needs the text to end
        // there or go on with a direction or a separator.
        val rest = m.groupValues[4].trim()
        if (prefix.length > 2 && rest.isNotEmpty() && !rest.matches(Regex("^(N|S|E|W|North|South|East|West)\\b.*|^[/·,(].*"))) return null
        return Parsed(shield, rest)
    }

    /** Width of the marker drawn [height] tall: wider for three figures. */
    fun width(shield: Shield, height: Float): Float = height * (if (shield.number.length >= 3) 1.28f else 1.0f)

    // Lazy: the parser is unit-tested on the JVM, where android.graphics does not exist.
    private val fill by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
    private val line by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND } }
    private val digits by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD); textAlign = Paint.Align.CENTER
        }
    }
    private val path by lazy { Path() }
    private val r by lazy { RectF() }

    private const val BLUE = 0xFF0B4EA2.toInt()
    private const val RED = 0xFFC8102E.toInt()
    private const val COUNTY_BLUE = 0xFF0A3F8F.toInt()
    private const val COUNTY_YELLOW = 0xFFFFD100.toInt()
    private const val INK = 0xFF0B0B0C.toInt()

    /** Draw [shield] with its top-left at ([left], [top]), [height] tall. Main thread only. Returns its width. */
    fun draw(canvas: Canvas, shield: Shield, left: Float, top: Float, height: Float): Float {
        val w = width(shield, height)
        val h = height
        fun x(f: Float) = left + w * f
        fun y(f: Float) = top + h * f
        line.strokeWidth = h * 0.045f
        var figure = Color.WHITE
        var centre = 0.60f
        var size = 0.52f
        when (shield.kind) {
            Kind.INTERSTATE -> {
                path.rewind()
                path.moveTo(x(0.05f), y(0.07f))
                path.quadTo(x(0.27f), y(0.14f), x(0.5f), y(0.02f))
                path.quadTo(x(0.73f), y(0.14f), x(0.95f), y(0.07f))
                path.cubicTo(x(1.03f), y(0.36f), x(0.97f), y(0.74f), x(0.5f), y(0.98f))
                path.cubicTo(x(0.03f), y(0.74f), x(-0.03f), y(0.36f), x(0.05f), y(0.07f))
                path.close()
                canvas.save()
                canvas.clipPath(path)
                fill.color = BLUE; canvas.drawRect(left, top, left + w, top + h, fill)
                fill.color = RED; canvas.drawRect(left, top, left + w, y(0.27f), fill)
                line.color = Color.WHITE; canvas.drawLine(left, y(0.27f), left + w, y(0.27f), line)
                canvas.restore()
                line.color = Color.WHITE; canvas.drawPath(path, line)
                centre = 0.64f; size = 0.46f
            }
            Kind.US -> {
                path.rewind()
                path.moveTo(x(0.09f), y(0.04f))
                path.quadTo(x(0.30f), y(0.13f), x(0.5f), y(0.04f))
                path.quadTo(x(0.70f), y(0.13f), x(0.91f), y(0.04f))
                path.quadTo(x(0.90f), y(0.19f), x(0.985f), y(0.31f))
                path.cubicTo(x(1.0f), y(0.63f), x(0.80f), y(0.87f), x(0.5f), y(0.985f))
                path.cubicTo(x(0.20f), y(0.87f), x(0.0f), y(0.63f), x(0.015f), y(0.31f))
                path.quadTo(x(0.10f), y(0.19f), x(0.09f), y(0.04f))
                path.close()
                fill.color = Color.WHITE; canvas.drawPath(path, fill)
                line.color = INK; canvas.drawPath(path, line)
                figure = INK; centre = 0.56f; size = 0.50f
            }
            Kind.STATE -> {
                fill.color = INK
                r.set(left, top, left + w, top + h)
                canvas.drawRoundRect(r, h * 0.12f, h * 0.12f, fill)
                fill.color = Color.WHITE
                r.inset(h * 0.07f, h * 0.07f)
                canvas.drawOval(r, fill)
                figure = INK; centre = 0.5f; size = 0.50f
            }
            Kind.COUNTY -> {
                path.rewind()
                path.moveTo(x(0.5f), y(0.98f))
                path.lineTo(x(0.02f), y(0.62f)); path.lineTo(x(0.15f), y(0.03f))
                path.lineTo(x(0.85f), y(0.03f)); path.lineTo(x(0.98f), y(0.62f))
                path.close()
                fill.color = COUNTY_BLUE; canvas.drawPath(path, fill)
                line.color = COUNTY_YELLOW; canvas.drawPath(path, line)
                figure = COUNTY_YELLOW; centre = 0.48f; size = 0.46f
            }
        }
        digits.color = figure
        digits.textSize = h * size
        // Three figures and a letter still have to sit inside the outline.
        val room = w * 0.74f
        val wide = digits.measureText(shield.number)
        if (wide > room) digits.textSize *= room / wide
        canvas.drawText(shield.number, left + w / 2f, y(centre) + digits.textSize * 0.36f, digits)
        return w
    }
}
