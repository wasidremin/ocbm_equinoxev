package wasidremin.gmccpa.ocbm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app half of updating the adapter (T20). The box half is tools/tests/fw_apply_test.sh. */
class AdapterFirmwareTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val good = "version 0123456789ab\n$a 755 /usr/sbin/carplayd\n$b 755 /script/radio_hal.sh\n"

    @Test fun aWellFormedManifestParses() {
        val m = AdapterFirmware.parseManifest(good)!!
        assertEquals("0123456789ab", m.version)
        assertEquals(listOf("/usr/sbin/carplayd", "/script/radio_hal.sh"), m.entries.map { it.path })
        // What the adapter reads back: the same lines with the nonce after the version.
        assertEquals("version 0123456789ab\nnonce 5f\n$a 755 /usr/sbin/carplayd\n$b 755 /script/radio_hal.sh\n", m.text("5f"))
    }

    /** A manifest that names the boot path, a strange folder or a bad hash is refused whole. */
    @Test fun anythingAnUpdateMustNotTouchIsRefused() {
        for (bad in listOf(
            "version 01\n$a 755 /script/ocbm_boot.sh\n",
            "version 01\n$a 755 /script/fw_apply.sh\n",
            "version 01\n$a 755 /script/run_ocbmd.sh\n",
            "version 01\n$a 755 /etc/passwd\n",
            "version 01\n$a 755 /usr/sbin/../../etc/x\n",
            "version 01\n$a 777 /usr/sbin/carplayd\n",
            "version 01\nnothex 755 /usr/sbin/carplayd\n",
            "version 01\n$a 755 /usr/sbin/carplayd\n$b 755 /usr/sbin/carplayd\n",
            "$a 755 /usr/sbin/carplayd\n",
            "version 01\n",
        )) assertNull(bad, AdapterFirmware.parseManifest(bad))
    }

    /** The version is a content id: the same files give the same id, any change a different one. */
    @Test fun theVersionIsTheContent() {
        val e = AdapterFirmware.parseManifest(good)!!.entries
        assertEquals(12, AdapterFirmware.versionOf(e).length)
        assertEquals(AdapterFirmware.versionOf(e), AdapterFirmware.versionOf(e.toList()))
        val changed = listOf(e[0].copy(sha256 = "c".repeat(64)), e[1])
        assertFalse(AdapterFirmware.versionOf(e) == AdapterFirmware.versionOf(changed))
    }

    /** The app build number travels with the manifest, so the adapter can refuse a downgrade. */
    @Test fun theBuildNumberIsCarried() {
        val m = AdapterFirmware.parseManifest("version 01\nseq 251\n$a 755 /usr/sbin/carplayd\n")!!
        assertEquals(251, m.seq)
        assertEquals("version 01\nnonce 5f\nseq 251\n$a 755 /usr/sbin/carplayd\n", m.text("5f"))
        assertNull(AdapterFirmware.parseManifest(good)!!.seq)
        assertNull(AdapterFirmware.parseManifest("version 01\nseq x1\n$a 755 /usr/sbin/carplayd\n"))
    }

    @Test fun stagedNamesFlattenThePath() {
        assertEquals("_usr_sbin_carplayd", AdapterFirmware.stagedName("/usr/sbin/carplayd"))
    }

    /** check.json is read only when it answers this request. */
    @Test fun checkAnswersAreMatchedByNonce() {
        val json = """{"ok":true,"nonce":"5f","differs":["/usr/sbin/carplayd"],"free_kb":4324,"pending":false,"prev":true,"version":"abc"}"""
        val c = AdapterFirmware.parseCheck(json, "5f")!!.getOrThrow()
        assertEquals(listOf("/usr/sbin/carplayd"), c.differs)
        assertEquals(4324L, c.freeKb)
        assertTrue(c.prev)
        assertFalse(c.pending)
        assertEquals("abc", c.version)
        assertNull("a stale answer", AdapterFirmware.parseCheck(json, "60"))
        assertNull(AdapterFirmware.parseCheck("not json", "5f"))
        val refused = AdapterFirmware.parseCheck("""{"ok":false,"nonce":"5f","error":"no manifest"}""", "5f")!!
        assertEquals("no manifest", refused.exceptionOrNull()?.message)
    }

    @Test fun applyAnswersCarryTheAdaptersReason() {
        assertEquals("def", AdapterFirmware.parseApply("""{"ok":true,"nonce":"5f","version":"def","files":1}""", "5f")!!.getOrThrow())
        val r = AdapterFirmware.parseApply("""{"ok":false,"nonce":"5f","error":"hash mismatch: /usr/sbin/btd"}""", "5f")!!
        assertEquals("hash mismatch: /usr/sbin/btd", r.exceptionOrNull()?.message)
        assertNull(AdapterFirmware.parseApply("""{"ok":true,"nonce":"zz"}""", "5f"))
    }

    /** Confirmed only with Bluetooth, the radio supervisor and the filesystem all healthy. */
    @Test fun healthThatConfirmsAnUpdate() {
        assertTrue(AdapterFirmware.healthy(0x73))   // HCI|SSP|btd|hostapd|rootfs-ok, the car's usual
        assertTrue(AdapterFirmware.healthy(0x51))
        assertFalse(AdapterFirmware.healthy(0x40))  // rootfs only: radios not up yet
        assertFalse(AdapterFirmware.healthy(0x42))
        assertFalse(AdapterFirmware.healthy(0x61))  // no btd
    }

    @Test fun infoFlagsAreRead() {
        val i = AdapterFirmware.parseInfo("""{"phone_order":true,"fw_update":true,"fw":"abc","fw_pending":true,"fw_prev":false}""")
        assertTrue(i.supported); assertEquals("abc", i.version); assertTrue(i.pending); assertFalse(i.prev)
        assertFalse(AdapterFirmware.parseInfo("""{"phone_order":true}""").supported)
        assertNotNull(AdapterFirmware.parseInfo("garbage"))
    }
}
