package wasidremin.gmccpa.ev

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import wasidremin.gmccpa.ProbeLog
import wasidremin.gmccpa.ocbm.AdapterSession
import wasidremin.gmccpa.ocbm.OcbmClient
import wasidremin.gmccpa.telemetry.InsightsData as P

/**
 * Tell CarPlay the car is electric, and keep it told how much charge and range are left
 * (docs/ops/08_FUTURE_TASKS.md T7). Settings ▸ Navigation ▸ "EV battery for Apple Maps".
 *
 * Two halves, both through the adapter:
 *  - **At connect** — [iapConfigYaml] goes into the subscribe document. The adapter turns it into the
 *    iPhone's Identify: EngineType Electric, this car's charging connectors, and the vehicle-status
 *    capabilities.
 *  - **While connected** — [start] reads the car's own properties every [PERIOD_S] seconds and, when
 *    the reading changed, pushes it to the adapter's `/tmp/vehicle_status` over `CH_FILE`. The
 *    adapter forwards it as `VehicleStatusUpdate` once the iPhone subscribes.
 *
 * Off by default. The Identify change has not yet been accepted by a phone, and a rejected Identify
 * costs that session its now-playing, cluster turn-by-turn and call metadata (CarPlay itself keeps
 * running). Turning it off takes effect at the next connect.
 *
 * Every Car API touch is wrapped: a missing property is left out of the file, never sent as zero, and
 * a failure here can never reach the CarPlay session.
 */
object CarPlayEv {
    private val log = ProbeLog.sub("ev")

    private const val PREFS = "carplay_ev"
    private const val KEY_ENABLED = "enabled"
    const val STATUS_PATH = "/tmp/vehicle_status"
    private const val PERIOD_S = 15L

    @Volatile var enabled: Boolean = false
        private set

    private var io: ScheduledExecutorService? = null
    @Volatile private var car: Car? = null
    /** The client the last push reached, and what it carried. A new client means a fresh adapter `/tmp`. */
    private var pushedTo: OcbmClient? = null
    private var pushedText: String? = null
    private var lastVerdict: String? = null

    fun load(ctx: Context) {
        enabled = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
        if (enabled) start(ctx)
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        enabled = on
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        log.i("EV battery for Apple Maps -> ${if (on) "ON" else "OFF"} (Identify changes at the next connect)")
        if (on) start(ctx) else stop()
    }

    fun statusText(ctx: Context): String =
        if (enabled) "On. Tells CarPlay the car is electric and sends battery and range. Takes effect at the next connect; turn off if now-playing or cluster directions stop working."
        else "Off. Tells CarPlay the car is electric and shares battery and range with Apple Maps. Beta; takes effect at the next connect."

    /** The subscribe-document block, or "" when off. Reads the car's connectors once per call. */
    fun iapConfigYaml(ctx: Context): String {
        if (!enabled) return ""
        val connectors = EvForCarPlay.connectorNames(readConnectors(ctx))
        log.i("EV identity for CarPlay: engine=electric connectors=$connectors")
        return EvForCarPlay.iapConfigYaml(connectors)
    }

    @Synchronized fun start(ctx: Context) {
        if (io != null) return
        val app = ctx.applicationContext
        io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ev-carplay").apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay({ runCatching { tick(app) } }, 0, PERIOD_S, TimeUnit.SECONDS) }
    }

    @Synchronized fun stop() {
        val e = io ?: return
        io = null
        e.execute { runCatching { car?.disconnect() }; car = null; pushedTo = null; pushedText = null }
        e.shutdown()
    }

    private fun tick(ctx: Context) {
        val client = AdapterSession.client ?: return
        val text = EvForCarPlay.statusText(sample(ctx))
        if (text.isEmpty()) return
        if (client === pushedTo && text == pushedText) return
        val ok = runCatching { client.filePush(STATUS_PATH, text.toByteArray(Charsets.UTF_8), 420) }.getOrDefault(false)
        if (ok) {
            if (client !== pushedTo) log.i("EV status pushed to the adapter: ${text.trim().replace('\n', ' ')}")
            pushedTo = client
            pushedText = text
        } else {
            log.w("EV status push to $STATUS_PATH failed — retrying in ${PERIOD_S}s")
        }
    }

    /** Shared by the feeder thread and the subscribe thread ([iapConfigYaml]). */
    @Synchronized private fun props(ctx: Context): CarPropertyManager {
        val c = car ?: (Car.createCar(ctx) ?: error("Car API unavailable")).also { car = it }
        return c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            ?: error("Car property service unavailable")
    }

    private fun sample(ctx: Context): EvForCarPlay.Sample {
        val pm = runCatching { props(ctx) }.getOrElse {
            verdict("EV status: Car API unavailable (${it.javaClass.simpleName}: ${it.message})")
            return EvForCarPlay.Sample()
        }
        // Raw ids from InsightsData, the set this app already reads on the Equinox (doc 14 §13).
        fun float(id: Int): Float? = runCatching {
            pm.getProperty(java.lang.Float::class.java, id, 0)?.takeIf { it.status == 0 }
                ?.let { (it.value as Number).toFloat() }
        }.getOrNull()
        fun bool(id: Int): Boolean? = runCatching {
            pm.getProperty(java.lang.Boolean::class.java, id, 0)?.takeIf { it.status == 0 }?.value as Boolean?
        }.getOrNull()
        fun int(id: Int): Int? = runCatching {
            pm.getProperty(Integer::class.java, id, 0)?.takeIf { it.status == 0 }
                ?.let { (it.value as Number).toInt() }
        }.getOrNull()
        // EV_CHARGE_STATE: 1 CHARGING. Without it, a connected port drawing power is charging.
        val charging = int(P.CHARGE_STATE)?.let { it == 1 }
            ?: bool(P.PORT_CONNECTED)?.let { connected -> connected && (float(P.CHARGE_MW) ?: 0f) > 0f }
        val s = EvForCarPlay.Sample(
            rangeMeters = float(P.RANGE_M),
            batteryWh = float(P.BATTERY_WH),
            capacityWh = float(P.CAPACITY_WH),
            charging = charging,
            outsideC = float(P.OUTSIDE_C),
            // On an EV, FUEL_LEVEL_LOW is the car's own low-battery warning.
            lowBattery = bool(P.FUEL_LOW),
        )
        verdict("EV status sources: range=${s.rangeMeters != null} battery=${s.batteryWh != null} " +
            "capacity=${s.capacityWh != null} charging=${s.charging != null} outside=${s.outsideC != null} " +
            "lowBattery=${s.lowBattery != null}")
        return s
    }

    private fun readConnectors(ctx: Context): IntArray? = runCatching {
        val v = props(ctx).getProperty(Array<Int>::class.java, VehiclePropertyIds.INFO_EV_CONNECTOR_TYPE, 0)
        v?.takeIf { it.status == 0 }?.value?.map { (it as Number).toInt() }?.toIntArray()
    }.onFailure { log.w("INFO_EV_CONNECTOR_TYPE unreadable (${it.javaClass.simpleName}) — using the Equinox EV's J1772 + CCS1") }
        .getOrNull()

    /** Log a source summary once per change, not every tick. */
    private fun verdict(v: String) {
        if (v != lastVerdict) { lastVerdict = v; log.i(v) }
    }
}
