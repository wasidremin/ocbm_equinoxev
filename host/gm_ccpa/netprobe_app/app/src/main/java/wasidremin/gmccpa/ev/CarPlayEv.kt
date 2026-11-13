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
 * **Charging stops in Apple Maps** need two more things in that update, the charging and
 * consumption models ([EvRoutingModel]). Only adapter firmware from 2026-11-13 relays them, and it
 * says so in its info (`"ev_models":true`). [iapConfigYaml] is told at each connect; with an older
 * adapter the Identify and the status file are exactly what they were before.
 *
 * Off by default. A rejected Identify costs that session its now-playing, cluster turn-by-turn and
 * call metadata (CarPlay itself keeps running). Turning it off takes effect at the next connect.
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

    /** Whether this connect's adapter relays the EV-routing models. Set by [iapConfigYaml]. */
    @Volatile private var routing = false
    /** How long a model input holds before it may change again; Maps re-plans when the models change. */
    private const val HOLD_MS = 60_000L
    private val capacityHold = EvRoutingModel.IntHold(HOLD_MS)
    private val auxHold = EvRoutingModel.IntHold(HOLD_MS)
    private val fullRangeHold = EvRoutingModel.IntHold(HOLD_MS)

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

    fun statusText(ctx: Context): String = when {
        !enabled -> "Off. Tells CarPlay the car is electric and shares battery and range with Apple Maps. Beta; takes effect at the next connect."
        routing -> "On, with charging stops. To add the car: forget the adapter on the iPhone, pair again, then Apple Maps ▸ your picture ▸ Vehicles. Turn off if now-playing or cluster directions stop working."
        else -> "On. Tells CarPlay the car is electric and sends battery and range. Charging stops in Apple Maps need newer adapter firmware. Turn off if now-playing or cluster directions stop working."
    }

    /**
     * The subscribe-document block, or "" when off. Reads the car's connectors once per call.
     * [adapterRelaysModels] is the adapter's own word (`"ev_models":true` in its info) and decides
     * for this whole connect whether the EV-routing fields are sent.
     */
    fun iapConfigYaml(ctx: Context, adapterRelaysModels: Boolean): String {
        if (!enabled) return ""
        routing = adapterRelaysModels
        val connectors = EvForCarPlay.connectorNames(readConnectors(ctx))
        if (!routing) {
            log.i("EV identity for CarPlay: engine=electric connectors=$connectors; this adapter's firmware does not relay the EV-routing models, so Apple Maps plans no charging stops")
            return EvForCarPlay.iapConfigYaml(connectors)
        }
        val name = EvRoutingModel.mapsName(readText(ctx, VehiclePropertyIds.INFO_MAKE), readText(ctx, VehiclePropertyIds.INFO_MODEL))
        log.i("EV identity for CarPlay: engine=electric connectors=$connectors, with the EV-routing models; Apple Maps name \"$name\"")
        return EvForCarPlay.iapConfigYaml(connectors, routing = true, mapsName = name)
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
        // The client outlives a dead link: on 2026-11-04 the 228 process, its link aborted the night
        // before, logged 115 failed pushes in 29 min.
        val client = AdapterSession.client?.takeIf { it.subscribed } ?: return
        val s = sample(ctx)
        val text = EvForCarPlay.statusText(s, if (routing) routingFor(s) else null)
        if (text.isEmpty()) return
        if (client === pushedTo && text == pushedText) return
        val ok = runCatching { client.filePush(STATUS_PATH, text.toByteArray(Charsets.UTF_8), 420) }.getOrDefault(false)
        if (ok) {
            if (client !== pushedTo) log.i("EV status pushed to the adapter: ${EvForCarPlay.logLine(text)}")
            pushedTo = client
            pushedText = text
        } else {
            log.w("EV status push to $STATUS_PATH failed — retrying in ${PERIOD_S}s")
        }
    }

    /**
     * The EV-routing half of the status file. Capacity, auxiliary power and full-charge range each
     * hold for [HOLD_MS] once sent, so the models change at most once a minute.
     */
    private fun routingFor(s: EvForCarPlay.Sample): EvForCarPlay.Routing {
        val now = android.os.SystemClock.elapsedRealtime()
        val capacity = capacityHold.value(EvRoutingModel.capacityFor(s.capacityWh), now)
        val aux = auxHold.value(EvRoutingModel.auxWatts(s.outsideC), now)
        // A pack too empty to scale from keeps the last figure it gave.
        val full = EvRoutingModel.fullRangeKm(s.rangeMeters, s.batteryWh, s.capacityWh)
            ?.let { fullRangeHold.value(it, now) } ?: fullRangeHold.current
        return EvForCarPlay.Routing(EvRoutingModel.chargingJson(capacity), EvRoutingModel.consumptionJson(aux), full)
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

    private fun readText(ctx: Context, id: Int): String? = runCatching {
        props(ctx).getProperty(String::class.java, id, 0)?.takeIf { it.status == 0 }?.value
    }.getOrNull()

    /** Log a source summary once per change, not every tick. */
    private fun verdict(v: String) {
        if (v != lastVerdict) { lastVerdict = v; log.i(v) }
    }
}
