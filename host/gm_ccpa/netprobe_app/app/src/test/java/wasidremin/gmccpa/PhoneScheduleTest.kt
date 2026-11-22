package wasidremin.gmccpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wasidremin.gmccpa.PhoneSchedule.Window

class PhoneScheduleTest {
    private val mine = "AA:BB:CC:DD:EE:01"
    private val hers = "AA:BB:CC:DD:EE:02"
    private val mon = 1; private val fri = 5; private val sat = 6; private val sun = 7
    private fun at(h: Int, m: Int = 0) = h * 60 + m

    /** The owner's example (2026-11-22): her phone tops the list, his is first on workdays until 6 PM. */
    private val owners = mapOf(mine to Window(PhoneSchedule.WEEKDAYS, 0, at(18)))
    private val list = listOf(hers, mine)

    @Test fun myPhoneOnWorkdaysUntilSixAndHersEveningsAndWeekends() {
        assertEquals(listOf(mine, hers), PhoneSchedule.orderNow(list, owners, mon, at(7, 30)))
        assertEquals(listOf(mine, hers), PhoneSchedule.orderNow(list, owners, fri, at(17, 59)))
        assertEquals("6 PM is hers", listOf(hers, mine), PhoneSchedule.orderNow(list, owners, fri, at(18)))
        assertEquals(listOf(hers, mine), PhoneSchedule.orderNow(list, owners, sat, at(10)))
        assertEquals(listOf(hers, mine), PhoneSchedule.orderNow(list, owners, sun, at(23, 59)))
    }

    @Test fun withNoScheduleTheListDecides() {
        assertEquals(list, PhoneSchedule.orderNow(list, emptyMap(), mon, at(9)))
    }

    @Test fun aWindowPastMidnightBelongsToTheDayItStarts() {
        val nights = Window(1 shl (fri - 1), at(22), at(6))
        assertTrue("Friday night", PhoneSchedule.covers(nights, fri, at(23)))
        assertTrue("the small hours after Friday", PhoneSchedule.covers(nights, sat, at(5, 59)))
        assertFalse(PhoneSchedule.covers(nights, sat, at(6)))
        assertFalse("Thursday night is not listed", PhoneSchedule.covers(nights, fri, at(2)))
        assertTrue("Sunday into Monday wraps the week", PhoneSchedule.covers(Window(1 shl (sun - 1), at(22), at(6)), mon, at(1)))
    }

    @Test fun equalTimesAreTheWholeDay() {
        val weekends = Window(PhoneSchedule.WEEKEND, 0, 0)
        assertTrue(PhoneSchedule.covers(weekends, sat, 0))
        assertTrue(PhoneSchedule.covers(weekends, sun, at(23, 59)))
        assertFalse(PhoneSchedule.covers(weekends, mon, at(12)))
    }

    @Test fun twoPhonesCoveredAtOnceKeepTheListOrder() {
        val both = mapOf(mine to Window(PhoneSchedule.EVERY_DAY, 0, 0), hers to Window(PhoneSchedule.EVERY_DAY, 0, 0))
        assertEquals(list, PhoneSchedule.orderNow(list, both, mon, at(9)))
    }

    @Test fun theListKeepsItsOrderForBondedPhonesAndAddsANewOneLast() {
        val third = "AA:BB:CC:DD:EE:03"
        assertEquals(listOf(hers, mine, third), PhoneSchedule.base(listOf(hers, mine), listOf(mine, third, hers)))
        assertEquals("a forgotten phone drops out", listOf(mine), PhoneSchedule.base(listOf(hers, mine), listOf(mine)))
        assertEquals("nothing kept: the adapter's order", listOf(mine, hers), PhoneSchedule.base(null, listOf(mine, hers)))
    }

    @Test fun theRowSaysTheWindowInWords() {
        assertEquals("Weekdays until 6 PM", PhoneSchedule.describe(Window(PhoneSchedule.WEEKDAYS, 0, at(18)), false))
        assertEquals("Weekends all day", PhoneSchedule.describe(Window(PhoneSchedule.WEEKEND, at(8), at(8)), false))
        assertEquals("Mon–Thu 6:30 AM to 5 PM", PhoneSchedule.describe(Window(0b0001111, at(6, 30), at(17)), false))
        assertEquals("Mon, Wed, Fri from 18:00", PhoneSchedule.describe(Window(0b0010101, at(18), 0), true))
        assertEquals("12 AM", PhoneSchedule.clock(0, false))
        assertEquals("12:15 PM", PhoneSchedule.clock(at(12, 15), false))
    }

    @Test fun aStoredWindowReadsBackAndAnythingElseIsDropped() {
        val w = Window(PhoneSchedule.WEEKDAYS, 0, at(18))
        assertEquals(w, PhoneSchedule.decode(PhoneSchedule.encode(w)))
        assertNull("no days", PhoneSchedule.decode("0,0,60"))
        assertNull(PhoneSchedule.decode("31,0,1440"))
        assertNull(PhoneSchedule.decode("31,0"))
        assertNull(PhoneSchedule.decode(null))
    }

    @Test fun theAdaptersBondsComeFromItsInfoInItsOrder() {
        assertEquals(listOf(hers, mine), PhoneBook.devicesOf("""{"phone_order":true,"devices":["aa:bb:cc:dd:ee:02","AA:BB:CC:DD:EE:01"]}"""))
        assertEquals(emptyList<String>(), PhoneBook.devicesOf("""{"phone_order":true}"""))
        assertEquals(emptyList<String>(), PhoneBook.devicesOf(null))
    }
}
