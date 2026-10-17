package wasidremin.gmccpa.av

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 4 tests for the device-free surface of [CarPlayLauncher]: record/step JSON and target
 * validation. The workflow itself ([CarPlayLauncher.launchBlocking]) needs a live session,
 * surface, and event channel, so it is verified on hardware; everything serializable is
 * verified here.
 */
class CarPlayLauncherTest {

    @Test fun targetRejectsOutOfRangeTapPoint() {
        try {
            CarPlayLauncher.LaunchTarget("maps", 1.2f, 0.5f)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        try {
            CarPlayLauncher.LaunchTarget("maps", 0.5f, -0.1f)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        // Edges are legal: corners address the panel extremes.
        CarPlayLauncher.LaunchTarget("corner", 0f, 0f)
        CarPlayLauncher.LaunchTarget("corner", 1f, 1f)
    }

    @Test fun targetRejectsBlankNameAndAbsurdDwell() {
        try {
            CarPlayLauncher.LaunchTarget("  ", 0.5f, 0.5f)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        try {
            CarPlayLauncher.LaunchTarget("maps", 0.5f, 0.5f, tapDwellMs = 10L)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun recordRoundTripsStepsOutcomeAndError() {
        val record = CarPlayLauncher.LaunchRecord(
            id = "20261014-120000-000",
            startedIso = "2026-10-14T12:00:00.000Z",
            targetName = "maps",
            nx = 0.25f,
            ny = 0.5f,
            verifyTemplate = "maps-icon",
            steps = listOf(
                CarPlayLauncher.LaunchStep("2026-10-14T12:00:01.000Z", "home", "AC Home sent", null, null),
                CarPlayLauncher.LaunchStep("2026-10-14T12:00:02.000Z", "tap", "tap DOWN/UP", null, null),
                CarPlayLauncher.LaunchStep("2026-10-14T12:00:03.000Z", "verify", "template at (10, 4)",
                    score = 0.9123f, passed = true),
            ),
            outcome = CarPlayLauncher.Outcome.SUCCESS,
            error = null,
        )
        val parsed = CarPlayLauncher.LaunchRecord.fromJson(JSONObject(record.toJson().toString()))
        assertEquals(record.id, parsed.id)
        assertEquals(record.startedIso, parsed.startedIso)
        assertEquals(record.targetName, parsed.targetName)
        assertEquals(record.nx, parsed.nx, 1e-6f)
        assertEquals(record.ny, parsed.ny, 1e-6f)
        assertEquals(record.verifyTemplate, parsed.verifyTemplate)
        assertEquals(record.outcome, parsed.outcome)
        assertNull(parsed.error)
        assertEquals(3, parsed.steps.size)
        assertNull(parsed.steps[0].score)
        assertNull(parsed.steps[0].passed)
        assertEquals(0.9123f, parsed.steps[2].score!!, 1e-4f)
        assertEquals(true, parsed.steps[2].passed)
    }

    @Test fun failedRecordKeepsErrorAndVerifyFailedOutcome() {
        val record = CarPlayLauncher.LaunchRecord(
            id = "20261014-120100-000",
            startedIso = "2026-10-14T12:01:00.000Z",
            targetName = "maps",
            nx = 0.25f,
            ny = 0.5f,
            verifyTemplate = "maps-icon",
            steps = listOf(
                CarPlayLauncher.LaunchStep("2026-10-14T12:01:03.000Z", "verify", "template at (0, 0)",
                    score = 0.31f, passed = false),
            ),
            outcome = CarPlayLauncher.Outcome.VERIFY_FAILED,
            error = "Verify \"maps-icon\" scored 0.310 < 0.85",
        )
        val parsed = CarPlayLauncher.LaunchRecord.fromJson(JSONObject(record.toJson().toString()))
        assertEquals(CarPlayLauncher.Outcome.VERIFY_FAILED, parsed.outcome)
        assertEquals(record.error, parsed.error)
    }

    @Test fun unknownOutcomeDefaultsToFailed() {
        val obj = JSONObject()
            .put("id", "x")
            .put("started", "")
            .put("target", "t")
            .put("nx", 0.5)
            .put("ny", 0.5)
            .put("steps", org.json.JSONArray())
            .put("outcome", "SOMETHING_NEW")
        assertEquals(CarPlayLauncher.Outcome.FAILED, CarPlayLauncher.LaunchRecord.fromJson(obj).outcome)
    }

    @Test fun nullOptionalsSurvive() {
        val record = CarPlayLauncher.LaunchRecord(
            id = "20261014-120200-000",
            startedIso = "2026-10-14T12:02:00.000Z",
            targetName = "home",
            nx = 0.5f,
            ny = 0.5f,
            verifyTemplate = null,
            steps = emptyList(),
            outcome = CarPlayLauncher.Outcome.SUCCESS,
            error = null,
        )
        val parsed = CarPlayLauncher.LaunchRecord.fromJson(JSONObject(record.toJson().toString()))
        assertNull(parsed.verifyTemplate)
        assertNull(parsed.error)
        assertTrue(parsed.steps.isEmpty())
    }
}
