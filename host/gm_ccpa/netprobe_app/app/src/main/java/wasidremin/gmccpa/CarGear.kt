package wasidremin.gmccpa

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context

/** The car's gear, read on demand for things allowed only in Park (call tests, the equalizer's bands). */
object CarGear {
    /** GEAR_SELECTION; an unreadable gear does not block (the emulator, a refused permission). Blocking. */
    fun parked(ctx: Context): Boolean = runCatching {
        val car = Car.createCar(ctx.applicationContext) ?: return@runCatching true
        try {
            val pm = car.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return@runCatching true
            pm.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0) == VehicleGear.GEAR_PARK
        } finally {
            car.disconnect()
        }
    }.getOrDefault(true)
}
