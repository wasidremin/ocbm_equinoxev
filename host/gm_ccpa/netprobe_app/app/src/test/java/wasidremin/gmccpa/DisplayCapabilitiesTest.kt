package wasidremin.gmccpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wasidremin.gmccpa.ocbm.VehicleConfigSpec
import wasidremin.gmccpa.ocbm.VehicleConfigYaml

class DisplayCapabilitiesTest {
    @Test fun defaultsRemainBytePinned() {
        assertTrue(VehicleConfigYaml.defaultIsPinned())
    }

    @Test fun theVideoOfferIsOffUntilTheAppCanPlayIt() {
        assertTrue("  enablesVideoPlayback: false\n" in VehicleConfigYaml.render(VehicleConfigSpec()))
        assertTrue("  enablesVideoPlayback: true\n" in VehicleConfigYaml.render(VehicleConfigSpec(videoPlayback = true)))
    }

    @Test fun aSessionThatStartsParkedTellsTheAdapterVideoMayPlayFromItsSubscribe() {
        fun doc(offer: Boolean, parked: Boolean) = VehicleConfigYaml.render(
            VehicleConfigSpec(videoPlayback = offer, videoAllowed = VehicleConfigYaml.videoWord(offer, parked)))
        assertTrue("\nvideo_allowed: true\n" in doc(offer = true, parked = true))
        assertTrue("\nvideo_allowed: false\n" in doc(offer = true, parked = false))
        assertFalse("no offer, no word", "video_allowed" in doc(offer = false, parked = true))
        assertFalse("the pinned default is unchanged", "video_allowed" in VehicleConfigYaml.render(VehicleConfigSpec()))
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
