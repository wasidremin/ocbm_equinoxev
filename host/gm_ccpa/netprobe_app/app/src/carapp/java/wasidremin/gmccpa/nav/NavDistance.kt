package wasidremin.gmccpa.nav

import androidx.car.app.model.Distance
import java.util.Locale

/**
 * iAP2 distance -> Car App Library [Distance].
 *
 * The phone's DISPLAY pair wins: Apple's `*DisplayString` is the number CarPlay itself shows, already
 * rounded, in the units of its `*DisplayUnits` enum, and Apple's rule is to show it verbatim without
 * conversion (`metadata` crate `types.rs` `DistanceUnit`). The raw meters are "graphics only", so
 * they are used only when the display pair is missing, through carlink's locale formatter
 * (`navigation/DistanceFormatter.kt`, public domain), whose thresholds this keeps.
 */
internal object NavDistance {
    // Apple DistanceUnit, metadata crate types.rs.
    private const val APPLE_KM = 0
    private const val APPLE_MILES = 1
    private const val APPLE_METERS = 2
    private const val APPLE_YARDS = 3
    private const val APPLE_FEET = 4

    private const val METERS_PER_FOOT = 0.3048
    private const val METERS_PER_MILE = 1609.344
    private const val FEET_THRESHOLD_M = 305 // ~1000 ft
    private const val KM_THRESHOLD_M = 1000

    /** carlink's imperial set (ISO 3166-1 alpha-2). */
    private val IMPERIAL_COUNTRIES = setOf("US", "GB", "MM", "LR")

    /** Null only when there is nothing at all to go on. */
    fun of(text: String?, appleUnits: Int?, meters: Long?, locale: Locale = Locale.getDefault()): Distance? {
        fromDisplay(text, appleUnits)?.let { return it }
        if (meters == null || meters < 0) return null
        val imperial = when (appleUnits) {
            APPLE_MILES, APPLE_YARDS, APPLE_FEET -> true
            APPLE_KM, APPLE_METERS -> false
            else -> locale.country in IMPERIAL_COUNTRIES
        }
        return if (imperial) imperial(meters) else metric(meters)
    }

    /** The phone's own string, e.g. "0.3" + miles -> 0.3 mi (one decimal), "500" + feet -> 500 ft. */
    internal fun fromDisplay(text: String?, appleUnits: Int?): Distance? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when (appleUnits) {
            APPLE_KM, APPLE_MILES -> {
                val (value, decimal) = parseDecimal(t) ?: return null
                val unit = if (appleUnits == APPLE_KM) {
                    if (decimal) Distance.UNIT_KILOMETERS_P1 else Distance.UNIT_KILOMETERS
                } else {
                    if (decimal) Distance.UNIT_MILES_P1 else Distance.UNIT_MILES
                }
                Distance.create(value, unit)
            }
            APPLE_METERS, APPLE_YARDS, APPLE_FEET -> {
                // Whole units: any separator is a thousands separator ("1,000 ft", "1.000 m").
                val value = t.filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: return null
                val unit = when (appleUnits) {
                    APPLE_METERS -> Distance.UNIT_METERS
                    APPLE_YARDS -> Distance.UNIT_YARDS
                    else -> Distance.UNIT_FEET
                }
                Distance.create(value, unit)
            }
            else -> null
        }
    }

    /**
     * "0.3", "0,3" (a comma-decimal locale), "12", "1,234.5". A single separator followed by one or
     * two digits is the decimal point; any other separator groups thousands. Returns the value and
     * whether the phone showed a fractional digit (which picks the `_P1` display unit).
     */
    internal fun parseDecimal(t: String): Pair<Double, Boolean>? {
        val s = t.filter { it.isDigit() || it == '.' || it == ',' }
        if (s.isEmpty() || s.none { it.isDigit() }) return null
        val lastSep = s.indexOfLast { it == '.' || it == ',' }
        val decimal = lastSep >= 0 && s.length - lastSep - 1 in 1..2
        val normalized = if (decimal) {
            s.substring(0, lastSep).filter { it.isDigit() } + "." + s.substring(lastSep + 1)
        } else {
            s.filter { it.isDigit() }
        }
        val v = normalized.toDoubleOrNull() ?: return null
        return v to decimal
    }

    private fun imperial(meters: Long): Distance =
        if (meters < FEET_THRESHOLD_M) {
            // Nearest 50 ft with a 50 ft floor: the cluster's own arrival glyph carries "you are
            // there", and "0 ft" reads as a fault.
            val feet = meters / METERS_PER_FOOT
            Distance.create((Math.round(feet / 50.0) * 50).coerceAtLeast(50).toDouble(), Distance.UNIT_FEET)
        } else {
            Distance.create(meters / METERS_PER_MILE, Distance.UNIT_MILES_P1)
        }

    private fun metric(meters: Long): Distance =
        if (meters < KM_THRESHOLD_M) {
            Distance.create((Math.round(meters / 50.0) * 50).coerceAtLeast(50).toDouble(), Distance.UNIT_METERS)
        } else {
            Distance.create(meters / 1000.0, Distance.UNIT_KILOMETERS_P1)
        }
}
