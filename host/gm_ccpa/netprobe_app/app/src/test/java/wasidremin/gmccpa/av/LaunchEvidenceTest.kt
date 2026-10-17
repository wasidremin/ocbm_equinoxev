package wasidremin.gmccpa.av

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * v101 diagnostics tests for the host-testable surface of [LaunchEvidence] and
 * [LaunchDiagnosticsBundle]. Actual PNG encoding, filesystem pruning, and the clear()
 * path need device bitmap/file APIs, so they are verified on hardware; the retention
 * rule, metadata envelope, score mapping, enablement rule, and record compatibility
 * are pinned here without adding a mocking dependency.
 */
class LaunchEvidenceTest {

    @Test fun retentionKeepsTwelveUnderBudget() {
        val names = (1..12).map { "v20261015-190500-%03d".format(it) }
        val sizes = names.associateWith { 1_000_000L }
        assertTrue(LaunchEvidence.retentionPlan(names, sizes).isEmpty())
    }

    @Test fun retentionDropsOldestFirstByCount() {
        val names = (1..14).map { "v20261015-190500-%03d".format(it) }
        val sizes = names.associateWith { 1_000_000L }
        assertEquals(names.take(2), LaunchEvidence.retentionPlan(names, sizes))
    }

    @Test fun retentionDropsOldestFirstByBytes() {
        val names = listOf("a", "b", "c")
        val sizes = mapOf("a" to 40L * 1024L * 1024L, "b" to 5L * 1024L * 1024L, "c" to 5L * 1024L * 1024L)
        assertEquals(listOf("a"), LaunchEvidence.retentionPlan(names, sizes))
    }

    @Test fun retentionBoundaryIsKept() {
        val names = (1..12).map { "v%02d".format(it) }
        // Exactly MAX_BYTES total: nothing is over budget.
        val each = LaunchEvidence.MAX_BYTES / 12
        val sizes = names.associateWith { each }
        assertTrue(LaunchEvidence.retentionPlan(names, sizes).isEmpty())
    }

    @Test fun retentionEmptyStaysEmpty() {
        assertTrue(LaunchEvidence.retentionPlan(emptyList(), emptyMap()).isEmpty())
    }

    @Test fun envelopeStampsWriterFieldsAndKeepsCallerFields() {
        val caller = JSONObject().put("role", "maps").put("outcome", "SUCCESS").toString()
        val meta = LaunchEvidence.envelopeJson("v1", caller, before = true, verification = false)
        assertEquals("v1", meta.getString("attemptId"))
        assertEquals(1, meta.getInt("schemaVersion"))
        assertTrue(meta.getBoolean("beforeFrameAvailable"))
        assertFalse(meta.getBoolean("verificationFrameAvailable"))
        assertEquals("maps", meta.getString("role"))
        assertEquals("SUCCESS", meta.getString("outcome"))
    }

    @Test fun jsonScoreMapsMissingAndNonFiniteToNull() {
        assertNull(LaunchEvidence.jsonScore(null))
        assertNull(LaunchEvidence.jsonScore(Float.NaN))
        assertNull(LaunchEvidence.jsonScore(Float.POSITIVE_INFINITY))
        assertNull(LaunchEvidence.jsonScore(Float.NEGATIVE_INFINITY))
        assertEquals(0.9123, LaunchEvidence.jsonScore(0.9123f)!!, 1e-6)
    }

    @Test fun disabledDiagnosticsSavesNothing() {
        assertFalse(LaunchEvidence.shouldSaveEvidence(false, true, true))
        assertFalse(LaunchEvidence.shouldSaveEvidence(false, false, false))
    }

    @Test fun enabledDiagnosticsNeedsAFrame() {
        assertFalse(LaunchEvidence.shouldSaveEvidence(true, false, false))
        assertTrue(LaunchEvidence.shouldSaveEvidence(true, true, false))
        assertTrue(LaunchEvidence.shouldSaveEvidence(true, false, true))
        assertTrue(LaunchEvidence.shouldSaveEvidence(true, true, true))
    }

    @Test fun recordKeepsLegacyOutcomeWithVerificationBasis() {
        val record = CarPlayLauncher.LaunchRecord(
            id = "v20261015-190509-604",
            startedIso = "2026-10-15T19:05:09.000Z",
            targetName = "spotify",
            nx = 0.029f,
            ny = 0.44f,
            verifyTemplate = "strip.spotify",
            steps = emptyList(),
            outcome = CarPlayLauncher.Outcome.SUCCESS,
        )
        assertEquals("transition_and_surface_only", record.verificationBasis)
        assertFalse(record.targetAppIdentityConfirmed)
        val parsed = CarPlayLauncher.LaunchRecord.fromJson(JSONObject(record.toJson().toString()))
        assertEquals(CarPlayLauncher.Outcome.SUCCESS, parsed.outcome)
        assertEquals("transition_and_surface_only", parsed.verificationBasis)
        assertFalse(parsed.targetAppIdentityConfirmed)
    }

    @Test fun prunePlanRemovesStalePendingDirs() {
        val entries = listOf(
            LaunchEvidence.Entry(".v-crashed.pending", 9_000_000L),
            LaunchEvidence.Entry("v20261015-190500-001", 1_000_000L),
        )
        assertEquals(listOf(".v-crashed.pending"), LaunchEvidence.prunePlan(entries))
    }

    @Test fun prunePlanAppliesRetentionToCompletedOnly() {
        val completed = (1..14).map { LaunchEvidence.Entry("v%02d".format(it), 1_000_000L) }
        val plan = LaunchEvidence.prunePlan(completed.shuffled(java.util.Random(7)))
        assertEquals(listOf("v01", "v02"), plan)
    }

    @Test fun prunePlanIgnoresOtherHiddenEntries() {
        val entries = listOf(LaunchEvidence.Entry(".nomedia", 0L), LaunchEvidence.Entry("v1", 1L))
        assertTrue(LaunchEvidence.prunePlan(entries).isEmpty())
    }

    @Test fun pendingGateBoundsInFlightWrites() {
        val gate = PendingGate(2)
        assertTrue(gate.tryAcquire())
        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
        gate.release()
        assertTrue(gate.tryAcquire())
        assertEquals(2, gate.inFlight)
    }

    @Test fun pendingGateReleaseNeverGoesNegative() {
        val gate = PendingGate(1)
        gate.release()
        assertEquals(0, gate.inFlight)
        assertTrue(gate.tryAcquire())
    }

    @Test fun evidenceIdValidation() {
        assertTrue(LaunchEvidence.isValidId("v20261015-190509-604"))
        assertFalse(LaunchEvidence.isValidId("../escape"))
        assertFalse(LaunchEvidence.isValidId(""))
        assertFalse(LaunchEvidence.isValidId("a".repeat(97)))
    }

    @Test fun legacyRecordWithoutVerificationFieldsParsesToDefaults() {
        // v100-era JSON has neither field: outcome keeps its meaning, basis defaults honestly.
        val obj = JSONObject()
            .put("id", "v-old")
            .put("started", "2026-10-15T19:05:09.000Z")
            .put("target", "maps")
            .put("nx", 0.029)
            .put("ny", 0.332)
            .put("steps", org.json.JSONArray())
            .put("outcome", "SUCCESS")
        val parsed = CarPlayLauncher.LaunchRecord.fromJson(obj)
        assertEquals(CarPlayLauncher.Outcome.SUCCESS, parsed.outcome)
        assertEquals("transition_and_surface_only", parsed.verificationBasis)
        assertFalse(parsed.targetAppIdentityConfirmed)
    }
}
