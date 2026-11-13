package wasidremin.gmccpa.gps

import android.Manifest
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.HandlerThread
import android.os.SystemClock
import wasidremin.gmccpa.ProbeLog
import wasidremin.gmccpa.ocbm.AdapterSession

/**
 * Give Apple Maps the car's own position (docs/ops/08_FUTURE_TASKS.md T6). Settings ▸ CarPlay ▸
 * "Car GPS for Apple Maps", off by default.
 *
 * Two halves, both through the adapter, the same shape as [wasidremin.gmccpa.ev.CarPlayEv]:
 *  - **At connect** — [iapConfigLines] adds a `locationInformation:` block to the subscribe document.
 *    The adapter turns it into Identify param 22 and the three location messages.
 *  - **While connected** — each fix from the car's receiver, about one a second, goes to the adapter
 *    as NMEA sentences ([Nmea]) with the car's wheel speed and gear. The adapter forwards them once
 *    the iPhone asks.
 *
 * Only adapter firmware from 2026-11-13 takes the fixes, and it says so in its info
 * (`"car_gps":true`). With an older adapter nothing is declared and nothing is sent.
 *
 * A fix is a coordinate. This file logs counts and ages, never a fix or a sentence.
 */
object CarGps {
    private val log = ProbeLog.sub("gps")

    private const val PREFS = "car_gps"
    private const val KEY_ENABLED = "enabled"
    /** Fixes with nobody to send them to before the receiver is let go until the next connect. */
    private const val IDLE_FIXES = 60
    private const val REPORT_MS = 300_000L

    @Volatile var enabled: Boolean = false
        private set
    /** Whether this connect declared the feed. Set by [iapConfigLines]. */
    @Volatile private var declared = false

    private var thread: HandlerThread? = null
    private var listener: LocationListener? = null
    @Volatile private var car: Car? = null
    private var sent = 0L
    private var reportedSent = 0L
    private var reportedAtMs = 0L
    private var idle = 0

    fun load(ctx: Context) {
        enabled = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        enabled = on
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        log.i("Car GPS for Apple Maps -> ${if (on) "ON" else "OFF"} (Identify changes at the next connect)")
        if (!on) stop("turned off")
    }

    fun statusText(ctx: Context): String = when {
        !enabled -> "Off. Sends the car's own position and wheel speed to the iPhone, so Apple Maps keeps its place in tunnels and garages. Beta; takes effect at the next connect."
        !permitted(ctx) -> "On, but this app has no location permission, so nothing is sent."
        declared -> "On. The iPhone gets the car's position about once a second. Turn off if now-playing or cluster directions stop working."
        else -> "On. Needs adapter firmware from 2026-11-13; takes effect at the next connect."
    }

    private fun permitted(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * The lines for the subscribe document's `iapConfig:` block, or "" when off, not permitted, or
     * the adapter does not take fixes. [adapterTakesFixes] is the adapter's own word
     * (`"car_gps":true` in its info). Starts the receiver when the feed is declared.
     */
    fun iapConfigLines(ctx: Context, adapterTakesFixes: Boolean): String {
        declared = false
        if (!enabled) return ""
        if (!adapterTakesFixes) {
            log.i("car GPS: this adapter's firmware does not take fixes — nothing declared")
            return ""
        }
        if (!permitted(ctx)) {
            log.w("car GPS: no location permission — nothing declared")
            return ""
        }
        declared = true
        start(ctx.applicationContext)
        log.i("car GPS declared for CarPlay: ${Nmea.SENTENCES}")
        return blockLines()
    }

    /** Pure, pinned by `CarGpsTest` and by the adapter's `the_gm_apps_location_block_resolves`. */
    fun blockLines(): String =
        "  locationInformation:\n    sentences: [" + Nmea.SENTENCES.joinToString(", ") + "]\n"

    @Synchronized private fun start(app: Context) {
        if (listener != null) return
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: run {
            log.w("car GPS: no location service"); return
        }
        val t = HandlerThread("car-gps").apply { start() }
        val l = LocationListener { fix -> runCatching { onFix(app, fix) } }
        val ok = runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, t.looper)
        }.onFailure { log.w("car GPS: could not start the receiver (${it.javaClass.simpleName})") }.isSuccess
        if (!ok) { t.quitSafely(); return }
        appContext = app
        thread = t
        listener = l
        sent = 0; reportedSent = 0; idle = 0
        reportedAtMs = SystemClock.elapsedRealtime()
        log.i("car GPS: receiver started")
    }

    @Synchronized private fun stop(why: String) {
        val l = listener ?: return
        listener = null
        appContext?.let { ctx ->
            runCatching { (ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.removeUpdates(l) }
        }
        thread?.quitSafely(); thread = null
        runCatching { car?.disconnect() }; car = null
        log.i("car GPS: receiver stopped — $why ($sent fixes sent)")
    }

    @Volatile private var appContext: Context? = null

    private fun onFix(app: Context, fix: Location) {
        appContext = app
        val client = AdapterSession.client?.takeIf { it.subscribed && declared }
        if (client == null) {
            if (++idle >= IDLE_FIXES) stop("no CarPlay session")
            return
        }
        idle = 0
        val (speed, gear) = wheel(app)
        val text = Nmea.sentences(
            Nmea.Fix(
                utcMs = fix.time,
                latDeg = fix.latitude,
                lonDeg = fix.longitude,
                altitudeM = if (fix.hasAltitude()) fix.altitude else null,
                speedMps = if (fix.hasSpeed()) fix.speed.toDouble() else null,
                courseDeg = if (fix.hasBearing()) fix.bearing.toDouble() else null,
                accuracyM = if (fix.hasAccuracy()) fix.accuracy.toDouble() else null,
                satellites = fix.extras?.getInt("satellites", 0)?.takeIf { it > 0 },
            ),
            speed, gear, SystemClock.elapsedRealtime(),
        )
        if (text.isEmpty()) return
        if (client.sendLocation(text)) sent++
        val now = SystemClock.elapsedRealtime()
        if (sent == 1L) log.i("car GPS: first fix sent to the adapter (${text.count { it == '\n' } + 1} sentences)")
        if (now - reportedAtMs >= REPORT_MS) {
            log.i("car GPS: ${sent - reportedSent} fixes sent in the last ${(now - reportedAtMs) / 1000} s ($sent this session)")
            reportedAtMs = now; reportedSent = sent
        }
    }

    /** The car's own speed and gear for `PASCD`; null speed leaves that sentence out. */
    private fun wheel(app: Context): Pair<Double?, Nmea.Gear> {
        val pm = runCatching {
            val c = car ?: (Car.createCar(app) ?: error("Car API unavailable")).also { car = it }
            c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        }.getOrNull() ?: return null to Nmea.Gear.UNKNOWN
        val speed = runCatching {
            pm.getProperty(java.lang.Float::class.java, VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)
                ?.takeIf { it.status == 0 }?.let { (it.value as Number).toDouble() }
        }.getOrNull()
        val gear = runCatching {
            when (pm.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0)) {
                VehicleGear.GEAR_PARK -> Nmea.Gear.PARK
                VehicleGear.GEAR_REVERSE -> Nmea.Gear.REVERSE
                VehicleGear.GEAR_NEUTRAL -> Nmea.Gear.NEUTRAL
                VehicleGear.GEAR_DRIVE -> Nmea.Gear.DRIVE
                else -> Nmea.Gear.UNKNOWN
            }
        }.getOrDefault(Nmea.Gear.UNKNOWN)
        return speed to gear
    }
}
