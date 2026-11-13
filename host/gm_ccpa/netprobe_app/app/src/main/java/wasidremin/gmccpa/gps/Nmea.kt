package wasidremin.gmccpa.gps

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.floor

/**
 * The car's position as the NMEA-0183 sentences CarPlay takes from an accessory
 * (docs/ops/08_FUTURE_TASKS.md T6): `GPGGA` (the fix), `GPRMC` (the fix with speed and course),
 * `GPHDT` (heading) and Apple's `PASCD` (wheel speed, which lets the phone keep moving the position
 * where satellites drop out).
 *
 * Pure: no Android types. Every sentence ends at its checksum, with no line ending; the adapter
 * refuses anything else. A sentence is a coordinate, so nothing here is ever logged.
 */
object Nmea {
    /** The names the adapter's config reader takes, in the order the sentences are sent. */
    val SENTENCES = listOf("gpgga", "gprmc", "gphdt", "pascd")

    /** Below this speed the direction of travel is noise, so no heading is sent. */
    const val MIN_SPEED_FOR_HEADING_MPS = 1.0

    data class Fix(
        val utcMs: Long,
        val latDeg: Double,
        val lonDeg: Double,
        val altitudeM: Double? = null,
        val speedMps: Double? = null,
        val courseDeg: Double? = null,
        val accuracyM: Double? = null,
        val satellites: Int? = null,
    )

    /** The car's transmission, as `PASCD` spells it. */
    enum class Gear(val code: Char) { DRIVE('D'), REVERSE('R'), NEUTRAL('N'), PARK('P'), UNKNOWN('U') }

    /** Every sentence this fix supports, one per line, ready for the adapter. Empty if the fix is unusable. */
    fun sentences(fix: Fix, wheelSpeedMps: Double?, gear: Gear, sensorMs: Long): String {
        if (!usable(fix)) return ""
        return listOfNotNull(gga(fix), rmc(fix), hdt(fix), wheelSpeedMps?.let { pascd(it, gear, sensorMs) })
            .joinToString("\n")
    }

    private fun usable(f: Fix): Boolean =
        f.latDeg.isFinite() && f.lonDeg.isFinite() && abs(f.latDeg) <= 90.0 && abs(f.lonDeg) <= 180.0 &&
            !(f.latDeg == 0.0 && f.lonDeg == 0.0) && f.utcMs > 0

    /** `$GPGGA,time,lat,N,lon,E,quality,satellites,hdop,altitude,M,,M,,*CS` */
    fun gga(f: Fix): String {
        val sats = (f.satellites ?: 8).coerceIn(0, 99)
        // The receiver gives an accuracy in metres, not a dilution figure; 5 m per unit is the usual rule of thumb.
        val hdop = ((f.accuracyM ?: 5.0) / 5.0).coerceIn(0.5, 50.0)
        val alt = f.altitudeM?.takeIf { it.isFinite() }?.let { fixed(it, 1) } ?: ""
        return close("GPGGA,${time(f.utcMs)},${lat(f.latDeg)},${lon(f.lonDeg)},1,${two(sats)},${fixed(hdop, 1)},$alt,M,,M,,")
    }

    /** `$GPRMC,time,A,lat,N,lon,E,knots,course,date,,,A*CS` */
    fun rmc(f: Fix): String {
        val knots = f.speedMps?.takeIf { it.isFinite() && it >= 0 }?.let { fixed(it * 1.943844, 1) } ?: ""
        val course = f.courseDeg?.takeIf { it.isFinite() }?.let { fixed(wrap(it), 1) } ?: ""
        return close("GPRMC,${time(f.utcMs)},A,${lat(f.latDeg)},${lon(f.lonDeg)},$knots,$course,${date(f.utcMs)},,,A")
    }

    /** `$GPHDT,heading,T*CS`, only while the car is moving fast enough for its course to mean something. */
    fun hdt(f: Fix): String? {
        val course = f.courseDeg?.takeIf { it.isFinite() } ?: return null
        if ((f.speedMps ?: 0.0) < MIN_SPEED_FOR_HEADING_MPS) return null
        return close("GPHDT,${fixed(wrap(course), 1)},T")
    }

    /**
     * `$PASCD,timestamp,C,gear,0,count,offset,speed*CS`: one wheel-speed sample. The timestamp is the
     * sensor's own clock in seconds, wrapping every day; `C` marks a combined (not per-wheel) speed;
     * the `0` says no wheel slip was detected; then the sample count and, for each sample, its offset
     * from the timestamp and the speed in metres per second.
     */
    fun pascd(speedMps: Double, gear: Gear, sensorMs: Long): String? {
        if (!speedMps.isFinite()) return null
        val ms = ((sensorMs % 86_400_000L) + 86_400_000L) % 86_400_000L
        val speed = abs(speedMps).coerceAtMost(150.0)
        val state = if (speedMps < 0 && gear == Gear.UNKNOWN) Gear.REVERSE else gear
        return close("PASCD,${ms / 1000}.${String.format(Locale.US, "%03d", ms % 1000)},C,${state.code},0,1,0.00,${fixed(speed, 3)}")
    }

    /** XOR of every character between `$` and `*`, as two upper-case hex digits. */
    fun checksum(body: String): String =
        String.format(Locale.US, "%02X", body.fold(0) { a, c -> a xor c.code } and 0xFF)

    private fun close(body: String) = "$" + body + "*" + checksum(body)

    private fun utc(ms: Long): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply { timeInMillis = ms }

    private fun time(ms: Long): String = utc(ms).let {
        String.format(Locale.US, "%02d%02d%02d.%02d", it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE),
            it.get(Calendar.SECOND), it.get(Calendar.MILLISECOND) / 10)
    }

    private fun date(ms: Long): String = utc(ms).let {
        String.format(Locale.US, "%02d%02d%02d", it.get(Calendar.DAY_OF_MONTH), it.get(Calendar.MONTH) + 1,
            it.get(Calendar.YEAR) % 100)
    }

    /** Degrees as NMEA's `ddmm.mmmm` with the hemisphere. Rounded first, so 59.99996 minutes cannot print as 60. */
    private fun lat(deg: Double) = dm(deg, 2) + "," + (if (deg >= 0) "N" else "S")
    private fun lon(deg: Double) = dm(deg, 3) + "," + (if (deg >= 0) "E" else "W")

    private fun dm(deg: Double, degDigits: Int): String {
        val tenThousandths = Math.round(abs(deg) * 60.0 * 10_000.0)     // minutes × 10⁴
        val d = tenThousandths / 600_000L
        val m = tenThousandths % 600_000L
        return String.format(Locale.US, "%0${degDigits}d%02d.%04d", d, m / 10_000L, m % 10_000L)
    }

    private fun wrap(deg: Double): Double = deg - 360.0 * floor(deg / 360.0)
    private fun two(n: Int) = String.format(Locale.US, "%02d", n)
    private fun fixed(v: Double, places: Int) = String.format(Locale.US, "%.${places}f", v)
}
