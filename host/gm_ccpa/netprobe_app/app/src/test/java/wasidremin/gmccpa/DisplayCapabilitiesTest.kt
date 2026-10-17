package wasidremin.gmccpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wasidremin.gmccpa.ocbm.VehicleConfigSpec
import wasidremin.gmccpa.ocbm.VehicleConfigYaml

class DisplayCapabilitiesTest {
    @Test fun defaultsRemainBytePinned() {
        assertTrue(VehicleConfigYaml.defaultIsPinned())
    }

    @Test fun physicalCalibrationChangesOnlyPanelMetadata() {
        val base = VehicleConfigSpec(width = 1024, height = 1100)
        val original = VehicleConfigYaml.render(base)
        for (mm in listOf(133, 200, 400)) {
            val extra = "    physicalDimensions:\n      width: $mm\n      height: 143\n"
            val calibrated = VehicleConfigYaml.render(base.copy(physicalWidthMm = mm, physicalHeightMm = 143))
            assertEquals(original, calibrated.replace(extra, ""))
        }
    }
}
