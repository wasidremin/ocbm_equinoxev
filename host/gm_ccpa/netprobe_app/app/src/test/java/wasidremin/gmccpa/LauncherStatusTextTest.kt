package wasidremin.gmccpa

import org.junit.Assert.*
import org.junit.Test

/**
 * v101 settings tests for the normal-path launcher status: a device with no user
 * overrides is Ready (built-ins are automatic), never "uncalibrated". Only a
 * genuinely missing template set is a profile error.
 */
class LauncherStatusTextTest {

    @Test fun noOverridesIsReadyNotUncalibrated() {
        val text = launcherStatusText(emptyList())
        assertTrue(text.contains("automatically"))
        assertTrue(text.contains("optional overrides"))
        assertFalse(
            "mandatory-calibration wording leaked into the normal path: $text",
            text.contains("uncalibrat", ignoreCase = true) ||
                text.contains("Missing", ignoreCase = false),
        )
    }

    @Test fun missingTemplatesAreAProfileError() {
        val text = launcherStatusText(listOf("maps", "phone"))
        assertTrue(text.startsWith("Profile error"))
        assertTrue(text.contains("maps"))
        assertTrue(text.contains("phone"))
        assertFalse(text.contains("uncalibrat", ignoreCase = true))
    }

    @Test fun singleMissingRoleNamesIt() {
        val text = launcherStatusText(listOf("spotify"))
        assertTrue(text.startsWith("Profile error"))
        assertTrue(text.contains("spotify"))
    }
}
