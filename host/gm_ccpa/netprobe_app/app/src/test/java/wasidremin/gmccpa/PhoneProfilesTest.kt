package wasidremin.gmccpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wasidremin.gmccpa.PhoneProfiles.Step

/** [PhoneProfiles]'s rule for a change of phone, and its stored form. Addresses are invented. */
class PhoneProfilesTest {
    private val owner = "AA:00:00:00:00:01"
    private val wife = "AA:00:00:00:00:02"

    @Test fun theSamePhoneAgainChangesNothing() {
        assertEquals(Step(null, null), PhoneProfiles.step(owner, owner, setOf(owner, wife)))
    }

    @Test fun aChangeOfPhoneKeepsTheValuesAsThePreviousPhonesAndPutsTheNextOnesInPlace() {
        assertEquals(Step(owner, wife), PhoneProfiles.step(owner, wife, setOf(owner, wife)))
    }

    @Test fun aPhoneSeenForTheFirstTimeKeepsTheValuesAsTheyAre() {
        // The owner's values are kept as theirs; nothing is loaded, so the wife starts from the same.
        assertEquals(Step(owner, null), PhoneProfiles.step(owner, wife, setOf(owner)))
        // The very first phone: nothing to keep, nothing to load.
        assertEquals(Step(null, null), PhoneProfiles.step(null, owner, emptySet()))
    }

    @Test fun aWeekOfTwoDriversEndsWithEachOnTheirOwn() {
        // Simulated: the owner sets Bass, the wife arrives and picks Vocal, the owner comes back.
        val profiles = mutableMapOf<String, Map<String, Any?>>()
        var inPlace: Map<String, Any?> = mapOf("sound/eq_preset" to "bass")
        var current: String? = owner
        fun drive(phone: String) {
            val s = PhoneProfiles.step(current, phone, profiles.keys)
            s.saveAs?.let { profiles[it] = inPlace }
            s.loadFrom?.let { inPlace = profiles.getValue(it) }
            current = phone
        }
        drive(wife)
        assertEquals("bass", inPlace["sound/eq_preset"])      // her first time: the settings as they were
        inPlace = mapOf("sound/eq_preset" to "vocal")          // she picks Vocal
        drive(owner)
        assertEquals("bass", inPlace["sound/eq_preset"])
        drive(wife)
        assertEquals("vocal", inPlace["sound/eq_preset"])
    }

    @Test fun theStoredValuesSurviveAndOnlyKnownSettingsAreTaken() {
        val values = mapOf("sound/eq_preset" to "vocal", "media_pane/percent" to 40, "media_pane/shown" to true,
            "carplay_screen/mode" to 3, "sound/eq_custom" to null)
        val back = PhoneProfiles.decode(PhoneProfiles.encode(values))
        assertEquals("vocal", back["sound/eq_preset"])
        assertEquals(40, back["media_pane/percent"])
        assertEquals(true, back["media_pane/shown"])
        assertEquals(false, back.containsKey("sound/eq_custom"))
        assertTrue(PhoneProfiles.decode("""{"upload/base_url":"x","sound/speed_loudness":"strong"}""").keys == setOf("sound/speed_loudness"))
        assertEquals(emptyMap<String, Any>(), PhoneProfiles.decode("not json"))
        assertEquals(emptyMap<String, Any>(), PhoneProfiles.decode(null))
    }
}
