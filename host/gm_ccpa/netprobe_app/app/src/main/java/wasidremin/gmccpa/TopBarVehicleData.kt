package wasidremin.gmccpa

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Cosmetic, activity-owned reads. Never guess units or display an unavailable/stale value. */
class TopBarVehicleData(private val view: GmTopBarView) {
    private val log = ProbeLog.sub("topbar")
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "topbar-vehicle") }
    @Volatile private var stopped = false
    private var car: Car? = null
    private var lastVerdict: String? = null

    fun start() {
        view.profileInitial = DisplayPrefs.lastPhoneName(view.context)?.let {
            String(Character.toChars(it.codePointAt(0))).uppercase()
        } ?: ""
        // Telephony signal strength needs READ_PHONE_STATE on the targeted API.
        // No runtime permission request (nor invented 5G indicator) for cosmetic chrome.
        view.signalBars = -1
        log.i("topbar: cellular signal omitted — no READ_PHONE_STATE permission requested")
        io.scheduleWithFixedDelay({ refresh() }, 0, 15, TimeUnit.SECONDS)
    }

    private fun refresh() {
        if (stopped) return
        val result = runCatching {
            val c = car ?: (Car.createCar(view.context.applicationContext)
                ?: error("Car API unavailable")).also { car = it }
            val pm = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                ?: error("Car property service unavailable")
            val temp = pm.getProperty(java.lang.Float::class.java, VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE, 0)
            check(temp != null && temp.status == 0) { "outside temperature unavailable" }
            val units = pm.getProperty(Integer::class.java, VehiclePropertyIds.HVAC_TEMPERATURE_DISPLAY_UNITS, 0)
            check(units != null && units.status == 0) { "temperature display units unavailable" }
            val celsius = (temp.value as Number).toFloat()
            check(celsius.isFinite()) { "outside temperature invalid" }
            // VehicleUnit.CELSIUS / FAHRENHEIT, documented VHAL unit enum values.
            val value = when ((units.value as Number).toInt()) {
                0x30 -> celsius
                0x31 -> celsius * 9f / 5f + 32f
                else -> error("temperature display units unsupported")
            }
            "${value.roundToInt()}°"
        }
        val verdict = result.getOrNull()?.let { "topbar: outside temperature=$it (vehicle units)" }
            ?: "topbar: outside temperature hidden — ${result.exceptionOrNull()?.javaClass?.simpleName}: ${result.exceptionOrNull()?.message}"
        if (verdict != lastVerdict) { lastVerdict = verdict; log.i(verdict) }
        main.post { if (!stopped) view.temperature = result.getOrNull() }
    }

    fun stop() {
        stopped = true
        io.execute { runCatching { car?.disconnect() }; car = null }
        io.shutdown()
    }
}
