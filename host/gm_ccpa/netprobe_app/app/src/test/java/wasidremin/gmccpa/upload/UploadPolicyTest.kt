package wasidremin.gmccpa.upload

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException

/**
 * Phase 2 unit tests for the Android-free upload policy.
 *
 * [UploadPolicy] and [CountingRequestOutputStream] (with an injected fake clock) run on plain
 * JVM JUnit — no device, no Robolectric. [UploadCenter] itself is untestable here because it
 * initializes `Handler`s on load; it delegates to [UploadPolicy], so this covers its decisions.
 */
class UploadPolicyTest {

    // ---- failure classification -----------------------------------------------------------

    @Test fun clientErrorIsTerminal() {
        val st = UploadPolicy.classifyFailure(UploadPolicy.HttpStatusException(400, "HTTP 400"), allowQueue = true)
        assertTrue(st is UploadState.Failed)
        assertFalse((st as UploadState.Failed).retryable)
        assertEquals("HTTP 400", st.message)
    }

    @Test fun rateLimitAndTimeoutAreQueueable() {
        for (code in listOf(408, 429)) {
            val queued = UploadPolicy.classifyFailure(
                UploadPolicy.HttpStatusException(code, "HTTP $code"), allowQueue = true)
            assertTrue("code $code should queue", queued is UploadState.Queued)
            val failed = UploadPolicy.classifyFailure(
                UploadPolicy.HttpStatusException(code, "HTTP $code"), allowQueue = false)
            assertTrue("code $code should fail retryable", failed is UploadState.Failed)
            assertTrue((failed as UploadState.Failed).retryable)
        }
    }

    @Test fun serverErrorQueuesWhenAllowed() {
        val st = UploadPolicy.classifyFailure(UploadPolicy.HttpStatusException(503, "busy"), allowQueue = true)
        assertTrue(st is UploadState.Queued)
    }

    @Test fun invalidRequestNeverRetries() {
        val st = UploadPolicy.classifyFailure(IllegalArgumentException("bad URL"), allowQueue = true)
        assertEquals(UploadState.Failed("bad URL", retryable = false), st)
    }

    @Test fun ioFailureQueuesWhenAllowed() {
        val st = UploadPolicy.classifyFailure(SocketTimeoutException("timed out"), allowQueue = true)
        assertTrue(st is UploadState.Queued)
        val direct = UploadPolicy.classifyFailure(SocketTimeoutException("timed out"), allowQueue = false)
        assertTrue(direct is UploadState.Failed && (direct as UploadState.Failed).retryable)
    }

    // ---- retry ----------------------------------------------------------------------------

    @Test fun successRunsBlockOnce() {
        var calls = 0
        val out = UploadPolicy.executeWithOneRetry(sleepMs = { fail("must not sleep") }) {
            calls++
            "ok"
        }
        assertEquals("ok", out)
        assertEquals(1, calls)
    }

    @Test fun transientFailureRetriesOnceThenSucceeds() {
        var calls = 0
        var slept = 0L
        var retried: Throwable? = null
        val out = UploadPolicy.executeWithOneRetry(
            onRetry = { retried = it },
            sleepMs = { slept = it },
        ) {
            calls++
            if (calls == 1) throw SocketTimeoutException("flaky")
            "recovered"
        }
        assertEquals("recovered", out)
        assertEquals(2, calls)
        assertEquals(UploadPolicy.RETRY_BACKOFF_MS, slept)
        assertTrue(retried is SocketTimeoutException)
    }

    @Test fun persistentTransientRethrowsAfterSecondAttempt() {
        var calls = 0
        try {
            UploadPolicy.executeWithOneRetry(sleepMs = {}) {
                calls++
                throw UploadPolicy.HttpStatusException(503, "down")
            }
            fail("expected HttpStatusException")
        } catch (t: UploadPolicy.HttpStatusException) {
            assertEquals(503, t.code)
        }
        assertEquals(2, calls)
    }

    @Test fun nonTransientNeverRetries() {
        var calls = 0
        try {
            UploadPolicy.executeWithOneRetry(sleepMs = { fail("must not sleep") }) {
                calls++
                throw UploadPolicy.HttpStatusException(400, "bad")
            }
            fail("expected HttpStatusException")
        } catch (t: UploadPolicy.HttpStatusException) {
            assertEquals(400, t.code)
        }
        assertEquals(1, calls)
    }

    // ---- multipart envelope ---------------------------------------------------------------

    @Test fun envelopeMathHolds() {
        val json = "{\"id\":\"x\"}".toByteArray(Charsets.UTF_8)
        val (prefix, suffix, total) = UploadPolicy.buildMultipartEnvelope("20261014-1", "BOUND", json, 1000L)
        assertEquals(prefix.size.toLong() + 1000L + suffix.size.toLong(), total)
        val prefixText = prefix.toString(Charsets.UTF_8)
        assertTrue(prefixText.contains("--BOUND"))
        assertTrue(prefixText.contains("name=\"metadata\""))
        assertTrue(prefixText.contains("name=\"image\""))
        assertTrue(prefixText.contains("20261014-1.png"))
        assertTrue(suffix.toString(Charsets.UTF_8).contains("--BOUND--"))
    }

    // ---- progress stream ------------------------------------------------------------------

    @Test fun progressEmitsFirstAndCompletion() {
        var now = 0L
        val emissions = mutableListOf<Pair<Long, Long?>>()
        val sink = ByteArrayOutputStream()
        val total = 200L * 1024L
        CountingRequestOutputStream(sink, total, clockMs = { now }) { sent, tot ->
            emissions += sent to tot
        }.use { out ->
            // First write emits immediately even though thresholds are not met.
            out.write(ByteArray(1024))
            assertEquals(1, emissions.size)
            // 63 KB more inside the same 100 ms window: still gated (needs both time AND bytes).
            now += 50L
            out.write(ByteArray(63 * 1024))
            assertEquals(1, emissions.size)
            // Past both 100 ms and 64 KB since last emission: emits.
            now += 60L
            out.write(ByteArray(1024))
            assertEquals(2, emissions.size)
            // Fill to total: completion always emits.
            now += 10L
            out.write(ByteArray((total - 65L * 1024L).toInt()))
        }
        val (lastSent, lastTotal) = emissions.last()
        assertEquals(total, lastSent)
        assertEquals(total, lastTotal)
        assertTrue(emissions.size in 3..5)
    }

    @Test fun indeterminateTotalStillEmitsFirstWrite() {
        val emissions = mutableListOf<Long>()
        CountingRequestOutputStream(ByteArrayOutputStream(), null, clockMs = { 0L }) { sent, _ ->
            emissions += sent
        }.use { out ->
            out.write(ByteArray(10))
        }
        assertEquals(listOf(10L), emissions)
    }

    @Test fun singleByteWriteCounts() {
        var now = 0L
        val emissions = mutableListOf<Long>()
        CountingRequestOutputStream(ByteArrayOutputStream(), 2L, clockMs = { now }) { sent, _ ->
            emissions += sent
        }.use { out ->
            out.write(0xAB)
            out.write(0xCD)
        }
        assertEquals(listOf(1L, 2L), emissions)
    }
}
