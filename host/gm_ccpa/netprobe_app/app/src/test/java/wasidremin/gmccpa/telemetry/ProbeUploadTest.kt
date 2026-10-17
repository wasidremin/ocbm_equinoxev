package wasidremin.gmccpa.telemetry

import org.junit.Assert.*
import org.junit.Test

class ProbeUploadTest {
    @Test fun endpointAppendsProbePath() {
        assertEquals(
            "https://wasidremin.ddnsgeek.com/cloudbridge/probe",
            ProbeUpload.endpointFor("https://wasidremin.ddnsgeek.com/cloudbridge/"),
        )
        assertEquals("https://h/probe", ProbeUpload.endpointFor("https://h"))
    }
    @Test fun namesAreSanitized() {
        assertEquals("20261014-120000-000", ProbeUpload.sanitizeName("20261014-120000-000"))
        assertEquals("a_b_c", ProbeUpload.sanitizeName("a/b\\c"))
        assertEquals("report", ProbeUpload.sanitizeName("../.."))
        assertEquals("report", ProbeUpload.sanitizeName(null))
        assertTrue(ProbeUpload.sanitizeName("x".repeat(200)).length <= 64)
    }
    @Test fun cleartextIsRefused() {
        try {
            ProbeUpload.checkHttps("http://192.168.1.2:8780")
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { }
        ProbeUpload.checkHttps("https://wasidremin.ddnsgeek.com/cloudbridge")
    }
    @Test fun missingFileFailsBeforeNetworking() {
        try {
            ProbeUpload.uploadSync(java.io.File("/nonexistent/report.json"), "x", "https://example.com")
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { }
    }
}
