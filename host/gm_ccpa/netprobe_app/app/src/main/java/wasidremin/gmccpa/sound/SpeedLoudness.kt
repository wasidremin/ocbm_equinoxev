package wasidremin.gmccpa.sound

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.SystemClock
import wasidremin.gmccpa.ProbeLog
import java.util.Locale
import kotlin.math.abs

/**
 * Loudness by speed (T36, owner 2026-11-26: "start moving forward with the speed volume trick"). At speed
 * an EV's cabin is tyre and wind noise, which buries the bass and the treble before the middle, so the
 * driver rides the volume. This lifts the two ends of CarPlay's media audio as the car goes faster
 * ([ToneShaper]'s shelves), and leaves calls, Siri and navigation prompts alone (they play through
 * `VoiceRouter`, not `AacPlayer`).
 *
 * The decisions are pure ([target], [glide]); [SpeedFeed] reads the car's speed once a second.
 */
object SpeedLoudness {
    /** The setting. Full lift in dB at and above [FULL_KMH]. */
    enum class Level(val key: String, val label: String, val bassDb: Double, val trebleDb: Double) {
        OFF("off", "Off", 0.0, 0.0),
        GENTLE("gentle", "Gentle", 4.0, 2.5),
        STRONG("strong", "Strong", 7.0, 4.0),
    }

    /** No lift at town speeds; all of it from highway speed (40 and 110 km/h are 25 and 68 mph). */
    const val START_KMH = 40.0
    const val FULL_KMH = 110.0
    /** The fastest the lift may move, so a change of speed is never heard as a step. */
    const val GLIDE_DB_PER_S = 0.5
    /** A speed older than this counts as standing still (the car stopped answering, or no Car API). */
    const val STALE_MS = 5_000L

    data class Gains(val bassDb: Double, val trebleDb: Double)

    /** Pure: the lift for a speed. Linear from [START_KMH] to [FULL_KMH]. */
    fun target(speedKmh: Double, level: Level): Gains {
        val t = ((speedKmh - START_KMH) / (FULL_KMH - START_KMH)).coerceIn(0.0, 1.0)
        return Gains(level.bassDb * t, level.trebleDb * t)
    }

    /** Pure: [current] moved toward [target] by at most [GLIDE_DB_PER_S] times [dtSec] on each end. */
    fun glide(current: Gains, target: Gains, dtSec: Double): Gains {
        val step = GLIDE_DB_PER_S * dtSec.coerceIn(0.0, 1.0)
        fun toward(a: Double, b: Double) = if (abs(b - a) <= step) b else a + step * Math.signum(b - a)
        return Gains(toward(current.bassDb, target.bassDb), toward(current.trebleDb, target.trebleDb))
    }

    /** Pure: the log line, numbers only. */
    fun describe(speedKmh: Double, g: Gains, level: Level): String =
        String.format(Locale.US, "loudness by speed (%s): %.0f km/h, bass %+.1f dB, treble %+.1f dB",
            level.key, speedKmh, g.bassDb, g.trebleDb)

    fun fromKey(key: String?): Level = Level.values().firstOrNull { it.key == key } ?: DEFAULT
    val DEFAULT = Level.GENTLE

    private const val PREFS = "sound"
    private const val KEY = "speed_loudness"
    @Volatile var level: Level = DEFAULT
        private set

    fun load(ctx: Context) {
        level = fromKey(ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
    }

    fun setLevel(ctx: Context, l: Level) {
        level = l
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, l.key).apply()
        ProbeLog.sub("sound").i("loudness by speed set to ${l.key}")
    }

    /** The car's speed, read once a second while media plays. Not pure: the Car API. */
    object SpeedFeed {
        private val log by lazy { ProbeLog.sub("sound") }
        @Volatile private var kmh = 0.0
        @Volatile private var atMs = 0L
        @Volatile private var thread: Thread? = null
        private var car: Car? = null

        fun speedKmh(nowMs: Long = SystemClock.elapsedRealtime()): Double = if (nowMs - atMs <= STALE_MS) kmh else 0.0

        @Synchronized fun start(ctx: Context) {
            if (thread != null) return
            val app = ctx.applicationContext
            thread = Thread({
                var warned = false
                while (thread === Thread.currentThread()) {
                    val v = runCatching {
                        val c = car ?: (Car.createCar(app) ?: error("Car API unavailable")).also { car = it }
                        val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                        pm.getProperty(java.lang.Float::class.java, VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)
                            ?.takeIf { it.status == 0 }?.let { abs((it.value as Number).toDouble()) * 3.6 }
                    }.onFailure { if (!warned) { warned = true; log.w("loudness by speed: no speed (${it.javaClass.simpleName})") } }
                        .getOrNull()
                    if (v != null && v.isFinite()) { kmh = v; atMs = SystemClock.elapsedRealtime() }
                    try { Thread.sleep(1_000) } catch (_: InterruptedException) { break }
                }
            }, "speed-loudness").apply { isDaemon = true; start() }
        }

        @Synchronized fun stop() {
            thread?.interrupt(); thread = null
        }
    }

    /**
     * One player's state: the current lift, gliding toward the speed's. Called from the audio thread with
     * each decoded buffer; returns the gains to apply. Logs a line when the lift moves by 1 dB or more.
     */
    class Follower {
        private var current = Gains(0.0, 0.0)
        private var lastNs = 0L
        private var loggedBass = 0.0
        private val log by lazy { ProbeLog.sub("sound") }

        fun next(nowNs: Long = System.nanoTime()): Gains {
            val lvl = level
            val dt = if (lastNs == 0L) 0.0 else (nowNs - lastNs) / 1e9
            lastNs = nowNs
            val speed = SpeedFeed.speedKmh()
            current = glide(current, target(speed, lvl), dt)
            if (abs(current.bassDb - loggedBass) >= 1.0 || (current.bassDb == 0.0 && loggedBass != 0.0)) {
                loggedBass = current.bassDb
                log.i(describe(speed, current, lvl))
            }
            return current
        }

        fun reset() { current = Gains(0.0, 0.0); lastNs = 0L; loggedBass = 0.0 }
    }
}
