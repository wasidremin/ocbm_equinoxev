package wasidremin.gmccpa.av

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleDestinationTest {
    private fun candidates() = listOf(
        VehicleCandidate(
            "com.android.car.settings",
            "com.android.car.settings.Settings_Launcher_Homepage",
            "Settings",
        ),
        VehicleCandidate(
            "com.gm.vehicleinfo",
            "com.gm.vehicleinfo.presentation.main.MainActivity",
            "Vehicle Status",
        ),
        VehicleCandidate(
            "com.android.car.settings",
            "com.android.car.settings.FallbackHome",
            "",
        ),
        VehicleCandidate(
            "com.gm.hmi.vc",
            "com.gm.hmi.vc.presentation.main.VCMainActivity",
            "Controls",
        ),
        VehicleCandidate(
            "com.gm.hvac",
            "com.gm.hvac.presentation.main.MainActivity",
            "Climate",
        ),
        VehicleCandidate(
            "com.gm.hmi.energy",
            "com.gm.hmi.energy.ui.main.MainLauncherActivity",
            "Charging",
        ),
        VehicleCandidate(
            "com.gm.energyassist",
            "com.gm.energyassist.monitorui.MainActivity",
            "Charge Assist",
        ),
    )

    @Test fun carPrefersSettingsOverVehicleStatus() {
        val match = selectVehicleDestination(VehicleKind.CAR, candidates())
        assertTrue(match != null)
        assertEquals(
            "com.android.car.settings/com.android.car.settings.settings_launcher_homepage",
            "${match?.packageName}/${match?.className}".lowercase(),
        )
    }

    @Test fun carSkipsEmptyLabelFallback() {
        val onlyFallback = listOf(
            VehicleCandidate("com.android.car.settings", "com.android.car.settings.FallbackHome", ""),
            VehicleCandidate(
                "com.gm.vehicleinfo",
                "com.gm.vehicleinfo.presentation.main.MainActivity",
                "Vehicle Status",
            ),
        )
        val match = selectVehicleDestination(VehicleKind.CAR, onlyFallback)
        assertEquals("com.gm.vehicleinfo", match?.packageName)
    }

    @Test fun controlsPrefersControlsOverClimate() {
        val match = selectVehicleDestination(VehicleKind.CONTROLS, candidates())
        assertEquals("com.gm.hmi.vc", match?.packageName)
    }

    @Test fun energyPrefersChargingOverChargeAssist() {
        val match = selectVehicleDestination(VehicleKind.ENERGY, candidates())
        assertEquals("com.gm.hmi.energy", match?.packageName)
    }

    @Test fun noCandidateReturnsNull() {
        assertNull(selectVehicleDestination(VehicleKind.CAR, emptyList()))
        assertNull(
            selectVehicleDestination(
                VehicleKind.CAR,
                listOf(VehicleCandidate("com.example", "com.example.Main", "Music")),
            ),
        )
    }
}
