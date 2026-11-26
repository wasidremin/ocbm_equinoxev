package wasidremin.gmccpa.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Wi-Fi only holds only if every internet request goes through [Net.open] (owner, 2026-11-26). */
class NetTest {
    private val src = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    @Test fun nothingOpensAConnectionExceptNet() {
        val others = src.walk().filter { it.isFile && it.extension == "kt" && it.name != "Net.kt" }
            .filter { f -> f.readText().contains("openConnection(") }
            .map { it.relativeTo(src).path }.toList()
        assertEquals(emptyList<String>(), others)
    }

    @Test fun theRefusalIsAnIoExceptionSoEveryCallerAlreadyHandlesIt() {
        assertTrue(Net.WifiOnly() is java.io.IOException)
    }
}
