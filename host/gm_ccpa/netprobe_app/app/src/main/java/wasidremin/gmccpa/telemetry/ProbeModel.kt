package wasidremin.gmccpa.telemetry

import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Array as ReflectArray
import java.util.Locale

/** Temporary diagnostics only. No android.car dependency in this model. */
object ProbeModel {
    val permissions = listOf("CAR_INFO", "CAR_SPEED", "CAR_ENERGY", "CAR_ENERGY_PORTS",
        "CAR_POWERTRAIN", "CAR_EXTERIOR_ENVIRONMENT", "READ_CAR_DISPLAY_UNITS", "CAR_TIRES",
        "CAR_DYNAMICS_STATE", "READ_CAR_STEERING", "CAR_MILEAGE", "CAR_DRIVING_STATE",
        "CAR_IDENTIFICATION").map { "android.car.permission.$it" } +
        listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION")

    val headlines = listOf("INFO_MAKE", "INFO_MODEL", "INFO_MODEL_YEAR", "INFO_FUEL_TYPE",
        "INFO_EV_CONNECTOR_TYPE", "INFO_EV_BATTERY_CAPACITY", "INFO_EXTERIOR_DIMENSIONS",
        "EV_BATTERY_LEVEL", "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE", "EV_CHARGE_PORT_CONNECTED",
        "EV_CHARGE_PORT_OPEN", "RANGE_REMAINING", "PERF_VEHICLE_SPEED", "PERF_VEHICLE_SPEED_DISPLAY",
        "WHEEL_TICK", "PERF_ODOMETER", "CURRENT_GEAR", "GEAR_SELECTION", "PARKING_BRAKE_ON",
        "IGNITION_STATE", "ENV_OUTSIDE_TEMPERATURE", "TIRE_PRESSURE", "DISTANCE_DISPLAY_UNITS")

    // Extracted from the repository's API-35 public android.car.jar (javap -constants).
    // Do not resolve headline fields on the VEHICLE: runtime absence is not compile-SDK absence.
    val compileIds = headlines.zip(listOf(286261505, 286261506, 289407235, 289472773,
        289472775, 291504390, 289472779, 291504905, 291504908, 287310603, 287310602,
        291504904, 291504647, 291504648, 290521862, 291504644, 289408001, 289408000,
        287310850, 289408009, 291505923, 392168201, 289408512)).toMap()

    fun hex(id: Int): String = String.format(Locale.ROOT, "0x%08X", id)
    fun group(id: Int): String = when (id and -0x10000000) {
        0x10000000 -> "SYSTEM"
        0x20000000 -> "VENDOR"
        0x30000000 -> "BACKPORTED"
        else -> "OTHER"
    }

    fun jsonValue(value: Any?): Any = when {
        value == null -> JSONObject.NULL
        value.javaClass.isArray -> JSONArray().also { a ->
            for (i in 0 until ReflectArray.getLength(value)) a.put(jsonValue(ReflectArray.get(value, i)))
        }
        value is Float && !value.isFinite() -> value.toString()
        value is Double && !value.isFinite() -> value.toString()
        value is Number || value is Boolean || value is String -> value
        else -> value.toString()
    }
    fun render(value: Any?): String = jsonValue(value).toString()
    fun csv(value: Any?): String = "\"${(value?.toString() ?: "").replace("\"", "\"\"")}\""
    fun error(t: Throwable): JSONObject = JSONObject().put("class", t.javaClass.name)
        .put("message", t.message ?: JSONObject.NULL)

    fun permissionClass(exists: Boolean, declared: Boolean, granted: Boolean, protection: Int): String = when {
        !exists -> "UNDEFINED"
        !declared -> "NOT_DECLARED"
        granted -> "GRANTED"
        (protection and 15) == 2 || (protection and 15) == 3 -> "DENIED_SIGNATURE_OR_PRIVILEGED"
        else -> "DENIED"
    }
    fun readClass(status: Int?, value: Any?, error: Throwable? = null): String = when {
        error is SecurityException -> "PRESENT_BUT_PERMISSION_DENIED"
        error != null && (error.javaClass.simpleName.contains("NotAvailable") ||
            error.javaClass.simpleName.contains("Unavailable")) -> "PRESENT_BUT_UNAVAILABLE"
        error != null -> "READ_FAILED"
        status == 1 || value == null -> "PRESENT_BUT_UNAVAILABLE"
        status != 0 -> "READ_FAILED"
        else -> "READABLE"
    }
    fun rate(changeMode: Int, min: Float, max: Float): Float? {
        if (changeMode == 1) return 0f
        if (changeMode != 2 || !min.isFinite() || !max.isFinite() || min < 0 || max < min || max <= 0) return null
        // Never subscribe above 5 Hz, even when an OEM config demands it.
        return 1f.coerceIn(min, max).takeIf { it <= 5f }
    }
    fun units(name: String): String = when (name) {
        "PERF_VEHICLE_SPEED", "PERF_VEHICLE_SPEED_DISPLAY" -> "m/s (signed direction)"
        "PERF_ODOMETER" -> "km"
        "RANGE_REMAINING" -> "m"
        "INFO_EV_BATTERY_CAPACITY", "EV_BATTERY_LEVEL" -> "Wh (not percent)"
        "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE" -> "mW; positive charging, negative discharge"
        "ENV_OUTSIDE_TEMPERATURE" -> "Celsius"
        "TIRE_PRESSURE" -> "kPa"
        "INFO_EXTERIOR_DIMENSIONS" -> "mm"
        "INFO_MODEL_YEAR" -> "year"
        "WHEEL_TICK" -> "cumulative signed ticks; configArray converts ticks to micrometers"
        else -> "Android enum/boolean/string or property-specific contract; unknown vendor units are not inferred"
    }
    const val WHEEL_CONTRACT = "Android contract (not vehicle evidence): vector = [reset count, front left, front right, rear right, rear left]. Counts increment forward and decrement in reverse; reset to zero at user vehicle start. A changed reset count invalidates comparisons across the boundary. configArray[0] is supported-wheel mask; [1..4] are micrometers per tick in the same wheel order. Unsupported wheels return zero. Validate config and reset behavior in real-car samples before choosing a distance source."
    const val CAVEAT = "The public property list is permission-filtered. ABSENT_FROM_PROPERTY_LIST means not visible to this UID at this census, not proof of VHAL absence. Subscription registration alone does not prove updates while moving. UX restrictions are an indirect UI signal, not exact gear, ignition or trip state. No preferred trip-distance source is confirmed until physical-vehicle captures are compared."

    const val AUTO_FLUSH_INTERVAL_MS = 60_000L
    const val AUTO_FLUSH_MIN_EVENTS = 10
    const val MAX_PENDING_REPORTS = 48

    fun shouldFlushChunk(
        autoEnabled: Boolean,
        capturing: Boolean,
        eventCount: Int,
        elapsedSinceFlushMs: Long,
        forceFlush: Boolean = false,
    ): Boolean {
        if (!autoEnabled || !capturing) return false
        if (forceFlush) return true
        return elapsedSinceFlushMs >= AUTO_FLUSH_INTERVAL_MS && eventCount >= AUTO_FLUSH_MIN_EVENTS
    }
}

/** Statistics per property AND area; invalid-status samples never contribute numerical extrema. */
class ProbeStats {
    var count = 0L
    private var firstAt = 0L
    private var lastAt = 0L
    private var first: Any = JSONObject.NULL
    private var last: Any = JSONObject.NULL
    private var min: Double? = null
    private var max: Double? = null
    private val distinct = HashSet<String>()
    private var distinctCapped = false
    private var valid = 0L
    private val elements = linkedMapOf<Int, ProbeStats>()
    fun add(at: Long, value: Any?, status: Int) {
        if (count == 0L) { firstAt = at; first = ProbeModel.jsonValue(value) }
        count++
        lastAt = at
        last = ProbeModel.jsonValue(value)
        if (status != 0 || value == null) return
        valid++
        val rendered = ProbeModel.render(value)
        if (distinct.size < 10000) distinct.add(rendered)
        else if (!distinct.contains(rendered)) distinctCapped = true
        if (value.javaClass.isArray) {
            for (i in 0 until ReflectArray.getLength(value))
                elements.getOrPut(i) { ProbeStats() }.add(at, ReflectArray.get(value, i), status)
        }
        (value as? Number)?.toDouble()?.takeIf { it.isFinite() }?.let {
            min = min?.coerceAtMost(it) ?: it
            max = max?.coerceAtLeast(it) ?: it
        }
    }
    fun json(): JSONObject = JSONObject().put("callbacks", count).put("availableCallbacks", valid)
        .put("first", first).put("last", last).put("min", min ?: JSONObject.NULL)
        .put("max", max ?: JSONObject.NULL).put("distinctValues", distinct.size)
        .put("distinctIsLowerBound", distinctCapped)
        .put("observedHz", if (count > 1 && lastAt > firstAt) (count - 1) * 1e9 / (lastAt - firstAt) else 0.0)
        .put("firstElapsedRealtimeNanos", firstAt).put("lastElapsedRealtimeNanos", lastAt)
        .put("arrayElementStatistics", JSONObject().also { out -> elements.forEach { (i, s) -> out.put(i.toString(), s.json()) } })
}
