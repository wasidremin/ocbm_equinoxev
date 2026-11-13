package wasidremin.gmccpa.ev

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wasidremin.gmccpa.telemetry.DriveOutlook

/** The two models Apple Maps plans charging from (docs/ops/08_FUTURE_TASKS.md T7). */
class EvRoutingModelTest {
    private fun JSONObject.numbers(key: String): List<Double> =
        getJSONArray(key).let { a -> (0 until a.length()).map { a.getDouble(it) } }

    /** The shape a working accessory sends: the formula id, then eleven, eleven and twenty-one numbers. */
    @Test fun theChargingModelHasTheShapeMapsAccepts() {
        val json = JSONObject(EvRoutingModel.chargingJson(85_500))
        assertEquals(12582922, json.getInt("model_id"))
        val p = json.getJSONObject("vehicle_parameters")
        val energy = p.numbers("vehicle_energy_axis_wh")
        val power = p.numbers("vehicle_charge_axis_w")
        val steps = p.numbers("energy_w_per_h")
        assertEquals(11, energy.size)
        assertEquals(11, power.size)
        assertEquals(21, steps.size)
        assertEquals(0.0, energy.first(), 0.0)
        assertEquals(85_500.0, energy.last(), 0.0)
        assertTrue("the energy axis rises", energy.zipWithNext().all { (a, b) -> b > a })
        assertEquals(150_000.0, power.max(), 0.0)
        assertEquals(0.0, steps.first(), 0.0)
        assertEquals(85_500.0, steps.last(), 0.0)
        assertTrue("5 % steps rise", steps.zipWithNext().all { (a, b) -> b > a })
        assertEquals(0.93, p.getDouble("efficiency_factor"), 1e-9)
    }

    @Test fun theConsumptionModelHasTheShapeMapsAccepts() {
        val json = JSONObject(EvRoutingModel.consumptionJson(750))
        assertEquals(12582921, json.getInt("model_id"))
        val p = json.getJSONObject("vehicle_parameters")
        assertEquals(750.0, p.getDouble("vehicle_auxiliary_power_w"), 0.0)
        assertEquals(10, p.numbers("vehicle_consumption_values_wh_per_m").size)
        assertEquals(7.0, p.getDouble("vehicle_altitude_gain_consumption_wh_per_m"), 0.0)
        assertEquals(4.6, p.getDouble("vehicle_altitude_loss_consumption_wh_per_m"), 0.0)
    }

    /** The adapter takes one printable line; a newline or a comma decimal would lose the model. */
    @Test fun bothModelsAreOnePrintableLine() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY) // decimal comma
            for (m in listOf(EvRoutingModel.chargingJson(85_000), EvRoutingModel.consumptionJson(2_450))) {
                assertTrue(m, m.all { it in ' '..'~' })
                assertTrue(m.startsWith("{") && m.endsWith("}"))
                JSONObject(m) // still parses
            }
            assertTrue(EvRoutingModel.consumptionJson(500).contains("\"vehicle_auxiliary_power_w\":500.0,"))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    /**
     * The two anchors of the consumption list are this car's own fit: steady 90 and 130 km/h on a
     * mild day, less the base auxiliary power, which the model counts by time instead.
     */
    @Test fun theConsumptionAnchorsFollowTheDriveLogsFit() {
        fun fit(kmh: Double): Double =
            (DriveOutlook.cruise(kmh / 3.6, DriveOutlook.MILD_C) - EvRoutingModel.AUX_BASE_W / kmh) / 1000.0
        assertEquals(fit(90.0), EvRoutingModel.CONSUMPTION_WH_PER_M[EvRoutingModel.ANCHOR_90_KMH], 0.0015)
        assertEquals(fit(130.0), EvRoutingModel.CONSUMPTION_WH_PER_M[EvRoutingModel.ANCHOR_130_KMH], 0.0015)
    }

    /** Climbing and descending come from the arrival model's mass and regeneration share. */
    @Test fun theSlopeTermsFollowTheCarsMass() {
        val perMetre = DriveOutlook.MASS_KG * 9.80665 / 3600.0
        assertEquals(perMetre * DriveOutlook.REGEN, EvRoutingModel.DESCENT_WH_PER_M, 0.1)
        // Uphill costs more than the bare lift (drivetrain loss), but not by more than a fifth.
        assertTrue(EvRoutingModel.CLIMB_WH_PER_M in perMetre..perMetre * 1.2)
    }

    /** Read back, the curve charges 10 to 80 % in the time the published tests measured (37 to 40 min). */
    @Test fun theCurveMatchesThePublishedChargeTime() {
        val cap = 85_000.0
        val e = EvRoutingModel.CHARGE_FRACTION.map { it * cap }
        val w = EvRoutingModel.CHARGE_WATTS.map { it.toDouble() }
        fun powerAt(wh: Double): Double {
            val i = (1 until e.size).first { wh <= e[it] }
            return w[i - 1] + (w[i] - w[i - 1]) * (wh - e[i - 1]) / (e[i] - e[i - 1])
        }
        var hours = 0.0
        var wh = 0.10 * cap
        val step = 50.0
        while (wh < 0.80 * cap) {
            hours += step / (powerAt(wh + step / 2) * EvRoutingModel.CHARGE_EFFICIENCY)
            wh += step
        }
        val minutes = hours * 60
        assertTrue("10-80 % took $minutes min", minutes in 36.0..41.0)
    }

    @Test fun auxiliaryPowerRisesWhenItIsCold() {
        assertEquals(500, EvRoutingModel.auxWatts(null))
        assertEquals(500, EvRoutingModel.auxWatts(18f))
        assertEquals(500, EvRoutingModel.auxWatts(30f))
        assertEquals(2_500, EvRoutingModel.auxWatts(8f))
        assertEquals(4_000, EvRoutingModel.auxWatts(-20f)) // capped
        assertEquals(500, EvRoutingModel.auxWatts(Float.NaN))
        // Whole steps, so a tenth of a degree does not change the model.
        assertEquals(0, EvRoutingModel.auxWatts(11.3f) % EvRoutingModel.AUX_STEP_W)
    }

    @Test fun fullRangeScalesTheCarsOwnFigure() {
        // 302 km left at 56 % is 539 km on a full pack, sent as 540.
        assertEquals(540, EvRoutingModel.fullRangeKm(302_000f, 47_880f, 85_500f))
        // A full pack reads as itself, and a reading above capacity is not scaled down.
        assertEquals(530, EvRoutingModel.fullRangeKm(531_000f, 85_500f, 85_500f))
        assertEquals(530, EvRoutingModel.fullRangeKm(531_000f, 86_000f, 85_500f))
        // Too empty to scale from, or a reading missing.
        assertNull(EvRoutingModel.fullRangeKm(40_000f, 8_000f, 85_500f))
        assertNull(EvRoutingModel.fullRangeKm(null, 47_880f, 85_500f))
        assertNull(EvRoutingModel.fullRangeKm(302_000f, null, 85_500f))
        assertNull(EvRoutingModel.fullRangeKm(302_000f, 47_880f, 0f))
    }

    @Test fun capacityMovesInSteps() {
        assertEquals(86_000, EvRoutingModel.capacityFor(85_900f))
        assertEquals(84_000, EvRoutingModel.capacityFor(83_900f))
        assertEquals(EvRoutingModel.NOMINAL_CAPACITY_WH, EvRoutingModel.capacityFor(null))
        assertEquals(EvRoutingModel.NOMINAL_CAPACITY_WH, EvRoutingModel.capacityFor(0f))
        assertEquals(EvRoutingModel.NOMINAL_CAPACITY_WH, EvRoutingModel.capacityFor(Float.NaN))
    }

    @Test fun connectorsCarryTheirPeakPower() {
        assertEquals(150_000, EvRoutingModel.connectorWatts("ccs1"))
        assertEquals(150_000, EvRoutingModel.connectorWatts("nacsdc"))
        assertEquals(11_500, EvRoutingModel.connectorWatts("j1772"))
        assertEquals(11_500, EvRoutingModel.connectorWatts("nacsac"))
    }

    /** The name goes inside double quotes in the subscribe document, so nothing that could end them. */
    @Test fun theMapsNameIsSafeInsideQuotes() {
        assertEquals("Chevrolet Equinox EV", EvRoutingModel.mapsName("Chevrolet", "Equinox EV"))
        assertEquals("Chevrolet Equinox EV", EvRoutingModel.mapsName(" Chevrolet ", "Equinox  EV\n"))
        assertEquals("Chevy Equinox", EvRoutingModel.mapsName("Chevy\"", "\\Equinox\u0000"))
        assertEquals("Equinox EV", EvRoutingModel.mapsName(null, "Equinox EV"))
        assertEquals(EvRoutingModel.FALLBACK_NAME, EvRoutingModel.mapsName(null, null))
        assertEquals(EvRoutingModel.FALLBACK_NAME, EvRoutingModel.mapsName("", "\"\""))
        assertEquals(EvRoutingModel.FALLBACK_NAME, EvRoutingModel.mapsName("2026", "4"))
        assertEquals(EvRoutingModel.FALLBACK_NAME, EvRoutingModel.mapsName("x".repeat(40), "y".repeat(40)))
    }

    /** A model input changes at most once per gap, so Maps is not made to re-plan every few seconds. */
    @Test fun aHeldValueChangesAtMostOncePerGap() {
        val hold = EvRoutingModel.IntHold(60_000)
        assertNull(hold.current)
        assertEquals(500, hold.value(500, 1_000))       // the first value is taken at once
        assertEquals(500, hold.value(700, 16_000))      // a change inside the gap waits
        assertEquals(500, hold.value(700, 60_999))
        assertEquals(700, hold.value(700, 61_000))      // and is taken once the gap has passed
        assertEquals(700, hold.value(700, 200_000))     // an unchanged value does not restart the gap
        assertEquals(700, hold.value(900, 120_999))
        assertEquals(900, hold.value(900, 121_000))
        assertEquals(900, hold.current)
    }
}
