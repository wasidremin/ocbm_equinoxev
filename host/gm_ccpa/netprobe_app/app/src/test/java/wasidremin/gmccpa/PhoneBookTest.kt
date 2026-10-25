package wasidremin.gmccpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings ▸ Phones: the adapter's bond list joined with names learned from CT_PHONE_IDENT. */
class PhoneBookTest {
    private val names = mapOf(
        "AA:BB:CC:DD:EE:01" to """{"name":"Justin's iPhone","model":"iPhone17,1"}""",
        "AA:BB:CC:DD:EE:02" to """{"name":"","model":""}""",
    )

    @Test fun keepsAdapterOrderAndMarksTheLivePhone() {
        val rows = PhoneBook.rows(listOf("aa:bb:cc:dd:ee:02", "AA:BB:CC:DD:EE:01"), "aa:bb:cc:dd:ee:01") { names[it] }
        assertEquals(listOf("AA:BB:CC:DD:EE:02", "AA:BB:CC:DD:EE:01"), rows.map { it.mac })
        assertFalse(rows[0].connected)
        assertTrue(rows[1].connected)
        assertEquals("Justin's iPhone", rows[1].label)
        assertEquals("iPhone17,1", rows[1].model)
    }

    @Test fun unnamedPhoneShowsByAddressTail() {
        val rows = PhoneBook.rows(listOf("AA:BB:CC:DD:EE:02", "11:22:33:44:55:66"), null) { names[it] }
        assertNull(rows[0].name)
        assertEquals("iPhone (EE:02)", rows[0].label)
        assertEquals("iPhone (55:66)", rows[1].label)
        assertTrue(rows.none { it.connected })
    }

    @Test fun dropsBlanksAndDuplicates() {
        val rows = PhoneBook.rows(listOf("", " AA:BB:CC:DD:EE:01 ", "aa:bb:cc:dd:ee:01"), "") { names[it] }
        assertEquals(1, rows.size)
        assertFalse(rows[0].connected)
    }
}
