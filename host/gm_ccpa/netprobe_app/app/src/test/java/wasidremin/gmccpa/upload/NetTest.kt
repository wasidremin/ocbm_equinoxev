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

    @Test fun bytesAreCountedOnlyOnceTheServerHasAnswered() {
        // 2026-11-23: three hours of retries with no connection went on the car's allowance.
        val early = src.walk().filter { it.isFile && it.extension == "kt" && it.name != "DataMeter.kt" }
            .flatMap { f ->
                val t = f.readText()
                Regex("""DataMeter\.record\(""").findAll(t).mapNotNull { m ->
                    val opened = t.lastIndexOf("Net.open(", m.range.first)
                    if (opened >= 0 && !t.substring(opened, m.range.first).contains("responseCode"))
                        "${f.relativeTo(src).path}:${t.substring(0, m.range.first).count { it == '\n' } + 1}" else null
                }
            }.toList()
        assertEquals(emptyList<String>(), early)
    }

    @Test fun theRefusalIsAnIoExceptionSoEveryCallerAlreadyHandlesIt() {
        assertTrue(Net.WifiOnly() is java.io.IOException)
    }
}
