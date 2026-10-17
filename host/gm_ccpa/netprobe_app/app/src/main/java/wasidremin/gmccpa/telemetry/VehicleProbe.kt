package wasidremin.gmccpa.telemetry

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyConfig
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.BufferedWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Created only by the manually opened diagnostic service. All mutable data belongs to worker. */
class VehicleProbe(private val context: Context, private val changed: () -> Unit,
    private val captureEnded: () -> Unit) {
    private val thread = HandlerThread("vehicle-telemetry").apply { start() }
    private val worker = Handler(thread.looper)
    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    private var ux: CarUxRestrictionsManager? = null
    private var uxRegistered = false
    private var currentUx = JSONObject().put("result", "NOT_OBSERVED")
    private var configs = emptyList<CarPropertyConfig<*>>()
    private var properties = JSONArray()
    private var permissions = JSONArray()
    private var headline = JSONArray()
    private val errors = JSONArray()
    private val subscriptions = JSONArray()
    private val stats = linkedMapOf<String, ProbeStats>()
    private var report = JSONObject()
    private var directory: File? = null
    private var events: BufferedWriter? = null
    private var csv: BufferedWriter? = null
    private var bytes = 0L
    private var censusComplete = false
    private var ended: String? = null
    private var closed = false
    private var gps: LocationManager? = null
    private var gpsRegistered = false
    private var rawGps = false
    private var captureStarted = 0L
    private var lastFlushMs = 0L
    private var chunkEvents = 0
    private var autoStreamMode = false
    private var sessionBootId: String? = null
    private var gpsStats = ProbeStats()
    @Volatile var connected = false; private set
    @Volatile var parked = false; private set
    @Volatile var capturing = false; private set
    @Volatile var summary = "Connecting to car service…"; private set
    @Volatile var latestDirectory: File? = null; private set

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).format(Date())
    private fun fail(where: String, t: Throwable) {
        errors.put(ProbeModel.error(t).put("operation", where).put("wallTime", now()))
    }
    private fun attempt(where: String, block: () -> Unit) {
        try { block() } catch (t: Exception) { fail(where, t) }
        catch (t: LinkageError) { fail(where, t) }
    }
    fun open() = worker.post {
        if (closed) return@post
        permissions = permissionSnapshot(context)
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            beginReport(); save()
            summary = "Car service unavailable: not an automotive platform. Normal app startup is unaffected."
            changed(); return@post
        }
        attempt("connect car service") {
            car = Car.createCar(context, worker, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                worker.post {
                    if (closed) { attempt("disconnect late connection") { c.disconnect() }; return@post }
                    connected = ready
                    if (!ready) {
                        parked = false
                        stopInternal("car service disconnected")
                        summary = "Car service disconnected; census visibility inconclusive"
                        changed()
                    } else {
                        attempt("get CarPropertyManager") { manager = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager }
                        attempt("get UX restrictions") {
                            ux = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                            ux?.currentCarUxRestrictions?.let { restrictions(it) }
                            ux?.registerListener { value -> worker.post { if (!closed) restrictions(value) } }
                            uxRegistered = ux != null
                        }
                        if (!capturing) censusInternal()
                        if (autoStreamMode && !capturing) {
                            startCaptureInternal(0, ProbeUpload.isAutoRawLocationEnabled(context), auto = true)
                        }
                    }
                }
            }
        }
        worker.postDelayed({ if (!connected && !closed) {
            summary = "Car service not connected (10-second observation); no vehicle access established"
            changed()
        } }, 10000)
    }
    private fun restrictions(value: CarUxRestrictions) {
        parked = !value.isRequiresDistractionOptimization
        currentUx = JSONObject().put("requiresDistractionOptimization", value.isRequiresDistractionOptimization)
            .put("activeRestrictions", value.activeRestrictions).put("observedElapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos())
            .put("meaning", "Indirect UI restriction signal; not exact gear, ignition, speed or trip state")
        if (capturing) event("UX_RESTRICTIONS", JSONObject().put("requiresDistractionOptimization",
            value.isRequiresDistractionOptimization).put("activeRestrictions", value.activeRestrictions))
        changed()
    }
    /** Fail closed on unknown/restricted UX; this is an interaction gate, not an assertion of gear. */
    private fun interactionAllowed(): Boolean {
        var allowed = false
        attempt("current UX interaction gate") {
            allowed = connected && ux?.currentCarUxRestrictions?.isRequiresDistractionOptimization == false
        }
        parked = allowed
        return allowed
    }
    fun census() = worker.post { if (!capturing && interactionAllowed()) censusInternal() }
    fun refreshPermissions(requested: Set<String> = emptySet()) = worker.post {
        permissions = permissionSnapshot(context, requested)
        if (!capturing && interactionAllowed()) censusInternal() else publishSummary()
    }
    private fun censusInternal() {
        if (closed || capturing) return
        permissions = permissionSnapshot(context)
        censusComplete = false
        configs = emptyList()
        properties = JSONArray()
        headline = JSONArray()
        attempt("getPropertyList") {
            val m = manager ?: throw IllegalStateException("CarPropertyManager unavailable")
            configs = m.propertyList.map { it as CarPropertyConfig<*> }.sortedBy { it.propertyId }
            censusComplete = true
        }
        for (config in configs) attempt("config ${ProbeModel.hex(config.propertyId)}") {
            val id = config.propertyId
            val name = propertyName(id)
            val areas = config.areaIds.takeIf { it.isNotEmpty() } ?: intArrayOf(0)
            val areaData = JSONArray()
            for (area in areas) {
                val a = JSONObject().put("areaId", area)
                attempt("limits ${ProbeModel.hex(id)} area=$area") {
                    a.put("minimum", ProbeModel.jsonValue(config.getMinValue(area)))
                    a.put("maximum", ProbeModel.jsonValue(config.getMaxValue(area)))
                }
                val read = JSONObject().put("areaId", area)
                // Even configs advertising WRITE_ONLY are recorded, but never written.
                if (config.access and 1 == 0) read.put("result", "READ_FAILED").put("reason", "config has no read access")
                else try {
                    val v = manager!!.getProperty<Any>(id, area)
                    read.put("result", ProbeModel.readClass(v?.status, v?.value))
                    read.put("status", v?.status ?: JSONObject.NULL).put("timestampNanos", v?.timestamp ?: JSONObject.NULL)
                    read.put("value", ProbeModel.jsonValue(v?.value))
                } catch (t: Exception) {
                    read.put("result", ProbeModel.readClass(null, null, t)).put("exception", ProbeModel.error(t))
                } catch (t: LinkageError) {
                    read.put("result", "READ_FAILED").put("exception", ProbeModel.error(t))
                }
                a.put("initialRead", read)
                areaData.put(a)
            }
            properties.put(JSONObject().put("id", id).put("hexId", ProbeModel.hex(id)).put("name", name)
                .put("group", ProbeModel.group(id)).put("javaType", config.propertyType.name)
                .put("access", config.access).put("accessName", when(config.access) { 1 -> "READ"; 2 -> "WRITE"; 3 -> "READ_WRITE (not tested for control)"; else -> "NONE" })
                .put("changeMode", config.changeMode).put("changeModeName", when(config.changeMode) { 0 -> "STATIC"; 1 -> "ON_CHANGE"; 2 -> "CONTINUOUS"; else -> "UNKNOWN" })
                .put("minimumSampleHz", config.minSampleRate).put("maximumSampleHz", config.maxSampleRate)
                .put("areaIds", ProbeModel.jsonValue(areas)).put("configurationArray", JSONArray(config.configArray))
                .put("areas", areaData).put("units", ProbeModel.units(name)))
        }
        for (name in ProbeModel.headlines) {
            val id = compileIds[name]
            val p = (0 until properties.length()).map { properties.getJSONObject(it) }.firstOrNull { it.optInt("id") == id }
            val h = JSONObject().put("name", name).put("id", id ?: JSONObject.NULL).put("units", ProbeModel.units(name))
            val results = p?.getJSONArray("areas")?.let { a -> (0 until a.length()).map {
                a.getJSONObject(it).getJSONObject("initialRead").getString("result") } } ?: emptyList()
            h.put("result", when {
                id == null -> "UNSUPPORTED_BY_COMPILE_SDK"
                !censusComplete -> "READ_FAILED"
                p == null -> "ABSENT_FROM_PROPERTY_LIST"
                "READABLE" in results -> "READABLE"
                "PRESENT_BUT_PERMISSION_DENIED" in results -> "PRESENT_BUT_PERMISSION_DENIED"
                "PRESENT_BUT_UNAVAILABLE" in results -> "PRESENT_BUT_UNAVAILABLE"
                else -> "READ_FAILED"
            }).put("areas", p?.optJSONArray("areas") ?: JSONArray())
            headline.put(h)
        }
        beginReport()
        save()
        publishSummary()
    }
    private fun beginReport() {
        if (directory != null) return
        val base = File(context.getExternalFilesDir(null) ?: context.filesDir, "telemetry").apply { mkdirs() }
        directory = File(base, SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date())).apply { mkdirs() }
        latestDirectory = directory
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        val boot = sessionBootId ?: directory!!.name.also { sessionBootId = it }
        report = JSONObject().put("schemaVersion", 1).put("package", context.packageName)
            .put("versionName", pi.versionName).put("versionCode", if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong())
            .put("buildType", if (context.applicationInfo.flags and 2 != 0) "debug" else "release")
            .put("releaseVariant", context.resources.getIdentifier("telemetry_build_variant", "string", context.packageName)
                .takeIf { it != 0 }?.let { context.getString(it) } ?: "unknown (non-Gradle build)")
            .put("androidVersion", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
            .put("fingerprint", Build.FINGERPRINT).put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("bootId", boot)
            .put("startWallTime", now()).put("startElapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos())
            .put("propertyListCaveat", ProbeModel.CAVEAT).put("wheelTickContract", ProbeModel.WHEEL_CONTRACT)
    }
    fun startAutoCapture(location: Boolean = ProbeUpload.isAutoRawLocationEnabled(context)) = worker.post {
        autoStreamMode = true
        rawGps = location
        if (closed) return@post
        if (connected && !capturing) {
            if (!censusComplete) censusInternal()
            startCaptureInternal(0, location, auto = true)
        }
    }
    fun flushChunkNow(reason: String = "periodic auto-upload flush") = worker.post {
        if (!capturing || closed || chunkEvents == 0) return@post
        rotateAndEnqueueChunk(reason)
    }
    fun startCapture(seconds: Int, location: Boolean) = worker.post {
        if (capturing) return@post
        if (closed || !interactionAllowed()) { captureEnded(); return@post }
        startCaptureInternal(seconds, location, auto = false)
    }
    private fun startCaptureInternal(seconds: Int, location: Boolean, auto: Boolean) {
        if (capturing || closed) return
        // A fresh directory preserves each previous capture instead of overwriting its raw samples.
        directory = null
        if (!auto) sessionBootId = null
        beginReport()
        rawGps = location
        ended = null
        stats.clear()
        for (i in 0 until headline.length()) {
            val h = headline.getJSONObject(i)
            if (h.optString("result") == "SUBSCRIBABLE") h.put("result", "READABLE")
            h.remove("liveCallbacks")
        }
        while (subscriptions.length() > 0) subscriptions.remove(0)
        gpsStats = ProbeStats()
        captureStarted = SystemClock.elapsedRealtimeNanos()
        lastFlushMs = SystemClock.elapsedRealtime()
        chunkEvents = 0
        try {
            events = File(directory, "events.ndjson").bufferedWriter()
            csv = File(directory, "events-${directory!!.name}.csv").bufferedWriter().apply {
                write("wall_time,elapsed_realtime_nanos,kind,property_id,hex_id,area,status,property_timestamp_nanos,value_json\n")
                flush()
            }
            bytes = 0
            capturing = true
            report.put("captureStartWallTime", now()).put("captureStartElapsedRealtimeNanos", captureStarted)
                .put("captureMode", when {
                    auto -> "continuous auto-upload stream"
                    seconds > 0 -> "60-second"
                    else -> "drive until manual stop or lifecycle/resource termination"
                })
                .put("rawLocationOptIn", rawGps)
            for (config in configs) {
                if (config.access and 1 == 0 || config.changeMode == 0) continue
                // All returned read-accessible changing properties are relevant to this capability census.
                val rate = ProbeModel.rate(config.changeMode, config.minSampleRate, config.maxSampleRate)
                val s = JSONObject().put("id", config.propertyId).put("name", propertyName(config.propertyId))
                    .put("requestedHz", rate ?: JSONObject.NULL)
                subscriptions.put(s)
                if (rate == null) { s.put("result", "SKIPPED_INVALID_OR_EXCESSIVE_RATE"); continue }
                try {
                    val accepted = manager!!.registerCallback(callback, config.propertyId, rate)
                    s.put("result", if (accepted) "REGISTERED_AWAITING_CALLBACK" else "REGISTRATION_REFUSED")
                } catch (t: Exception) { s.put("result", "SUBSCRIPTION_FAILED").put("exception", ProbeModel.error(t)) }
                catch (t: LinkageError) { s.put("result", "SUBSCRIPTION_FAILED").put("exception", ProbeModel.error(t)) }
            }
            ux?.currentCarUxRestrictions?.let { restrictions(it) }
            if (rawGps) startGps()
            save()
            if (seconds > 0) worker.postDelayed(stopTimer, seconds * 1000L)
            if (auto) scheduleFlushTick()
            publishSummary()
        } catch (t: Exception) { fail("start capture", t); stopInternal("capture setup failed") }
    }
    private val flushTick = Runnable {
        if (!capturing || closed || !autoStreamMode) return@Runnable
        val elapsed = SystemClock.elapsedRealtime() - lastFlushMs
        if (ProbeModel.shouldFlushChunk(ProbeUpload.isAutoUploadEnabled(context), capturing, chunkEvents, elapsed)) {
            rotateAndEnqueueChunk("periodic auto-upload flush")
        }
        scheduleFlushTick()
    }
    private fun scheduleFlushTick() {
        worker.removeCallbacks(flushTick)
        if (capturing && !closed && autoStreamMode) {
            worker.postDelayed(flushTick, ProbeModel.AUTO_FLUSH_INTERVAL_MS)
        }
    }
    private fun rotateAndEnqueueChunk(reason: String) {
        if (!capturing || directory == null) return
        val completedDir = directory!!
        attempt("close chunk events") { events?.close() }; events = null
        attempt("close chunk CSV") { csv?.close() }; csv = null
        ended = now()
        report.put("captureEndWallTime", ended).put("captureStopReason", reason)
            .put("captureEndElapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos())
        save()
        // Open next chunk directory without dropping CarPropertyManager or GPS callbacks
        directory = null
        ended = null
        chunkEvents = 0
        bytes = 0
        lastFlushMs = SystemClock.elapsedRealtime()
        beginReport()
        try {
            events = File(directory, "events.ndjson").bufferedWriter()
            csv = File(directory, "events-${directory!!.name}.csv").bufferedWriter().apply {
                write("wall_time,elapsed_realtime_nanos,kind,property_id,hex_id,area,status,property_timestamp_nanos,value_json\n")
                flush()
            }
            report.put("captureStartWallTime", now()).put("captureStartElapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos())
                .put("captureMode", "continuous auto-upload stream")
                .put("rawLocationOptIn", rawGps)
            save()
        } catch (t: Exception) {
            fail("rotate chunk", t)
        }
        ProbeUpload.enqueueAndDrain(context, reportDir = completedDir, activeDir = directory)
    }
    private val stopTimer = Runnable { stopInternal("60-second timer completed") }
    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            worker.post {
                if (!capturing || closed) return@post
                try {
                    val at = SystemClock.elapsedRealtimeNanos()
                    val key = "${value.propertyId}:${value.areaId}"
                    stats.getOrPut(key) { ProbeStats() }.add(at, value.value, value.status)
                    if (value.status == 0 && value.value != null) for (i in 0 until headline.length()) {
                        val h = headline.getJSONObject(i)
                        if (h.optInt("id") == value.propertyId) h.put("result", "SUBSCRIBABLE")
                    }
                    event("PROPERTY", JSONObject().put("id", value.propertyId).put("hexId", ProbeModel.hex(value.propertyId))
                        .put("areaId", value.areaId).put("status", value.status).put("timestampNanos", value.timestamp)
                        .put("value", ProbeModel.jsonValue(value.value)), at)
                    // Flush immediately on GEAR_SELECTION -> PARK transition during auto-stream so finished trips land right away
                    if (autoStreamMode && value.propertyId == 289408000 && (value.value as? Number)?.toInt() == 4 && chunkEvents >= 2) {
                        worker.postDelayed({ if (capturing && !closed && chunkEvents > 0) rotateAndEnqueueChunk("parked gear transition") }, 2500L)
                    }
                } catch (t: Exception) { fail("property callback", t); stopInternal("callback failure") }
            }
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            worker.post { if (capturing) event("PROPERTY_ERROR", JSONObject().put("id", propertyId).put("areaId", areaId)
                .put("message", "public callback reported error; no exact error code supplied")) }
        }
    }
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!capturing) return
            val at = SystemClock.elapsedRealtimeNanos()
            gpsStats.add(at, location.speed.takeIf { location.hasSpeed() }, 0)
            event("LOCATION_RAW_PRIVACY_SENSITIVE", JSONObject().put("provider", location.provider)
                .put("latitude", location.latitude).put("longitude", location.longitude)
                .put("accuracyMeters", location.accuracy).put("speedMps", if (location.hasSpeed()) location.speed else JSONObject.NULL)
                .put("locationElapsedRealtimeNanos", location.elapsedRealtimeNanos).put("locationWallTimeMillis", location.time), at)
        }
        override fun onProviderEnabled(provider: String) { if (capturing) event("LOCATION_PROVIDER_ENABLED", JSONObject().put("provider", provider)) }
        override fun onProviderDisabled(provider: String) { if (capturing) event("LOCATION_PROVIDER_DISABLED", JSONObject().put("provider", provider)) }
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }
    private fun startGps() = attempt("GPS subscription (explicit raw-coordinate opt-in)") {
        gps = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        report.put("gpsProviderEnabled", gps!!.isProviderEnabled(LocationManager.GPS_PROVIDER))
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            report.put("gpsSubscription", "DENIED: fine location not granted")
            return@attempt
        }
        try {
            gps!!.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, thread.looper)
            gpsRegistered = true
            report.put("gpsSubscription", "REGISTERED_AWAITING_FIX")
        } catch (t: SecurityException) {
            report.put("gpsSubscription", "DENIED").put("gpsException", ProbeModel.error(t))
            fail("GPS runtime denial", t)
        }
    }
    private fun event(kind: String, data: JSONObject, at: Long = SystemClock.elapsedRealtimeNanos()) {
        if (!capturing) return
        try {
            val e = JSONObject().put("wallTime", now()).put("elapsedRealtimeNanos", at).put("kind", kind).put("data", data)
            val line = e.toString()
            events!!.write(line); events!!.newLine(); events!!.flush()
            val fields = listOf(e.getString("wallTime"), at, kind, data.opt("id"), data.opt("hexId"),
                data.opt("areaId"), data.opt("status"), data.opt("timestampNanos"), data.opt("value") ?: data)
            csv!!.write(fields.joinToString(",") { ProbeModel.csv(it) }); csv!!.newLine(); csv!!.flush()
            bytes += line.toByteArray(Charsets.UTF_8).size
            chunkEvents++
            if (bytes >= 50L * 1024 * 1024 || directory!!.usableSpace < 100L * 1024 * 1024)
                stopInternal("resource limit: 50 MiB raw events or less than 100 MiB free disk")
        } catch (t: Exception) { fail("persist live event", t); stopInternal("event persistence failure") }
    }
    fun stop() = worker.post { if (interactionAllowed()) stopInternal("manual parked stop") }
    private fun stopInternal(reason: String) {
        val wasCapturing = capturing
        capturing = false
        worker.removeCallbacks(stopTimer)
        worker.removeCallbacks(flushTick)
        // Unregister the shared callback, including partially successful setup, on every stop.
        attempt("unregister property callbacks") { manager?.unregisterCallback(callback) }
        if (gpsRegistered) attempt("remove GPS callbacks") { gps?.removeUpdates(locationListener) }
        gpsRegistered = false
        attempt("close events") { events?.close() }; events = null
        attempt("close CSV") { csv?.close() }; csv = null
        if (wasCapturing) {
            ended = now()
            report.put("captureEndWallTime", ended).put("captureStopReason", reason)
                .put("captureEndElapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos())
            save()
            directory?.let { completed ->
                ProbeUpload.enqueueAndDrain(context, reportDir = completed, activeDir = null)
            }
            publishSummary(); captureEnded()
        }
    }
    private fun save() {
        if (directory == null) return
        attempt("save reports") {
            val statistics = JSONObject()
            for (i in 0 until headline.length()) {
                val h = headline.getJSONObject(i)
                val callbacks = stats.filterKeys { it.startsWith("${h.optInt("id") }:") }.values.sumOf { it.count }
                h.put("liveCallbacks", callbacks)
            }
            stats.forEach { (k, v) -> statistics.put(k, v.json()) }
            report.put("carServiceConnected", connected).put("censusCompleted", censusComplete)
                .put("currentUxRestrictions", currentUx)
                .put("permissions", permissions).put("properties", properties).put("headlines", headline)
                .put("subscriptions", subscriptions).put("subscriptionStatisticsByPropertyArea", statistics)
                .put("gpsStatistics", gpsStats.json()).put("errors", errors).put("reportUpdatedWallTime", now())
                .put("endWallTime", ended ?: JSONObject.NULL)
                .put("confirmedFacts", JSONArray().put("These observations belong only to this build/device fingerprint and this UID.")
                    .put("Public property census completed=$censusComplete, visible configurations=${configs.size}.")
                    .put("Read and callback results are recorded per property/area; no vehicle writes attempted."))
                .put("notTestedOrInconclusive", JSONArray().put(ProbeModel.CAVEAT)
                    .put("Motion context, UX transition reliability, reset behavior and preferred distance source need physical-vehicle review.")
                    .put(if (rawGps) "Raw GPS coordinates are included only in machine events/JSON/CSV by explicit opt-in." else "GPS provider/location updates not tested: raw location capture not enabled.")
                    .put("No callback for an ON_CHANGE property does not prove subscription failure; it may not have changed."))
            val dest = File(directory, "report.json")
            val tmp = File(directory, "report.json.tmp")
            tmp.bufferedWriter().use { out ->
                val base = report.toString(2)
                out.write(base.dropLast(1)); out.write(",\n\"rawLiveEvents\":[\n")
                val raw = File(directory, "events.ndjson")
                var first = true
                if (raw.exists()) raw.forEachLine { line ->
                    if (!first) out.write(",\n")
                    out.write(line); first = false
                }
                out.write("\n]}\n")
            }
            if (!tmp.renameTo(dest)) throw java.io.IOException("atomic report rename failed")
            File(directory, "report.md").writeText(markdown())
        }
    }
    private fun markdown(): String = buildString {
        append("# Vehicle Telemetry Probe — device observations\n\n")
        append(report.toString(2).let { // Exclude raw coordinates: only report metadata and GPS speed statistics here.
            val copy = JSONObject(it)
            copy.remove("properties"); copy.remove("headlines")
            copy.toString(2)
        })
        append("\n\n## Headline properties\n")
        for (i in 0 until headline.length()) append(headline.getJSONObject(i).toString(2)).append('\n')
        for (group in listOf("SYSTEM", "VENDOR", "BACKPORTED", "OTHER")) {
            append("\n## $group property census\n")
            for (i in 0 until properties.length()) {
                val p = properties.getJSONObject(i)
                if (p.getString("group") == group) append(p.toString(2)).append('\n')
            }
        }
        append("\n## Read-accessible HVAC\nConfig READ_WRITE is not proof this UID may control it; no control attempted.\n")
        for (i in 0 until properties.length()) {
            val p = properties.getJSONObject(i)
            if (p.getString("name").startsWith("HVAC_") && p.getInt("access") and 1 != 0) append(p.toString(2)).append('\n')
        }
    }
    private fun publishSummary() {
        var readable = 0
        var vendor = 0
        for (i in 0 until properties.length()) {
            val p = properties.getJSONObject(i)
            if (p.getString("group") == "VENDOR") vendor++
            val a = p.getJSONArray("areas")
            if ((0 until a.length()).any { a.getJSONObject(it).getJSONObject("initialRead").optString("result") == "READABLE" }) readable++
        }
        summary = buildString {
            append("Car service: ${if (connected) "connected" else "not connected"}\n")
            append("Visible=${configs.size}, readable=$readable, vendor=$vendor; capture=$capturing\n")
            append("UX: ${if (parked) "detailed interaction allowed" else "restricted or unknown"} (not exact driving state)\n")
            for (i in 0 until permissions.length()) {
                val p = permissions.getJSONObject(i)
                append("${p.getString("name")}: ${p.getString("classification")} exists=${p.optBoolean("exists")} declared=${p.optBoolean("declared")} protection=${p.opt("protectionLevel")} flags=${p.opt("protectionFlags")} runtime=${p.opt("runtimeRequest")}\n")
            }
            for (i in 0 until headline.length()) {
                val h = headline.getJSONObject(i)
                append("${h.getString("name")}: ${h.getString("result")} ${h.getJSONArray("areas")}\n")
            }
            append("\nLocal reports: ${directory?.absolutePath ?: "not yet created"}\n")
            append("\n${ProbeModel.CAVEAT}\nErrors: $errors")
        }
        changed()
    }
    fun clearData() = worker.post {
        if (closed || capturing || !interactionAllowed()) return@post
        attempt("clear probe data") {
            val base = File(context.getExternalFilesDir(null) ?: context.filesDir, "telemetry")
            if (base.exists() && !base.deleteRecursively()) throw java.io.IOException("Could not delete all probe reports")
            directory = null; latestDirectory = null; report = JSONObject()
            stats.clear(); ended = null
            while (errors.length() > 0) errors.remove(0)
            while (subscriptions.length() > 0) subscriptions.remove(0)
            summary = "Probe files cleared. Refresh census to create a new report."; changed()
        }
    }
    fun close() = worker.post {
        if (closed) return@post
        closed = true
        stopInternal("service lifecycle teardown")
        ended = ended ?: now()
        save()
        if (uxRegistered) attempt("unregister UX callback") { ux?.unregisterListener() }
        uxRegistered = false
        attempt("disconnect car") { car?.disconnect() }
        connected = false; parked = false
        thread.quitSafely()
    }
    companion object {
        // API-35 PUBLIC SDK constants; literal permission strings allow undefined names to be reported.
        private val compileIds = ProbeModel.compileIds
        fun propertyName(id: Int): String = try { VehiclePropertyIds.toString(id) } catch (_: LinkageError) {
            compileIds.entries.firstOrNull { it.value == id }?.key ?: "UNKNOWN_${ProbeModel.hex(id)}"
        }
        fun permissionSnapshot(context: Context, requested: Set<String> = emptySet()): JSONArray {
            val prefs = context.getSharedPreferences("vehicle_probe_permissions", Context.MODE_PRIVATE)
            if (requested.isNotEmpty()) prefs.edit().apply { requested.forEach { putBoolean(it, true) } }.apply()
            val declared = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions?.toSet() ?: emptySet()
            return JSONArray().also { output -> ProbeModel.permissions.forEach { name ->
                val p = JSONObject().put("name", name).put("declared", name in declared)
                try {
                    val info = context.packageManager.getPermissionInfo(name, 0)
                    val granted = context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
                    p.put("exists", true).put("protectionLevel", info.protectionLevel and 15)
                        .put("protectionFlags", info.protectionLevel and 15.inv())
                        .put("granted", granted).put("classification", ProbeModel.permissionClass(true, name in declared, granted, info.protectionLevel))
                } catch (t: PackageManager.NameNotFoundException) {
                    p.put("exists", false).put("granted", false).put("classification", "UNDEFINED").put("exception", ProbeModel.error(t))
                }
                p.put("runtimeRequest", if (prefs.getBoolean(name, false)) "API_REQUEST_SUBMITTED; OS dialog presentation is not observable" else "NOT_REQUESTED")
                    .put("runtimeDialogPresented", "UNKNOWN: OS may silently deny or suppress a request")
                output.put(p)
            } }
        }
    }
}
