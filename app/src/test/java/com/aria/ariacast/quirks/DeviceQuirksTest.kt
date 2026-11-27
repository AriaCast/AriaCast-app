package com.aria.ariacast.quirks

import com.aria.ariacast.ExtraFields
import org.junit.Assert.assertEquals
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeviceQuirksTest {
    @Before fun loadTheList() { TestSpeakers.load() }

    private fun ids(d: DeviceIdentity) = DeviceQuirks.matching(d).map { it.id }

    @Test fun matchesWiimOverDlnaByNameOrManufacturer() {
        assertEquals(listOf("wiim-dlna-restart"), ids(DeviceIdentity("DLNA", "Living room", "WiiM Pro Plus", "Linkplay Technology Inc.")))
        assertEquals(listOf("wiim-dlna-restart"), ids(DeviceIdentity("DLNA", "WiiM Mini-1234")))
    }

    @Test fun wiimOnAnotherProtocolIsNotMatched() {
        assertTrue(ids(DeviceIdentity("AirPlay2", "WiiM Pro Plus")).isEmpty())
    }

    @Test fun matchesShairportPerAirPlayVersion() {
        assertEquals(listOf("shairport-airplay2-ptp"), ids(DeviceIdentity("AirPlay2", "Kitchen", "ShairportSync")))
        assertEquals(listOf("shairport-airplay1"), ids(DeviceIdentity("AirPlay", "Kitchen", "ShairportSync")))
    }

    @Test fun sonosMatchesByProtocol() {
        assertEquals(listOf("sonos-airplay2"), ids(DeviceIdentity("AirPlay2", "Living", "Sonos Play:5")))
        assertEquals(listOf("sonos-dlna"), ids(DeviceIdentity("DLNA", "Living", "Play:5", "Sonos, Inc.")))
        assertTrue(ids(DeviceIdentity("Google Cast", "Living", "Sonos Play:5")).isEmpty())
    }

    @Test fun theDoctorGetsTheNoteInTheUsersLanguage() {
        val english = DeviceQuirks.notesFor(wiimDevice, Locale.ENGLISH)
        assertEquals(listOf("wiim-dlna-restart"), english.map { it.first.id })
        assertTrue(english.single().second.startsWith("WiiM players pause"))
        assertTrue(DeviceQuirks.notesFor(wiimDevice, Locale.ITALIAN).single().second.startsWith("I WiiM si fermano"))
        // A language the list has no text for falls back to English.
        assertTrue(DeviceQuirks.notesFor(wiimDevice, Locale.JAPANESE).single().second.startsWith("WiiM players pause"))
        assertTrue(DeviceQuirks.notesFor(DeviceIdentity("DLNA", "Living", "Play:5", "Sonos, Inc."), Locale.ENGLISH).isEmpty())
    }

    @Test fun configComesFromTheMatchingEntries() {
        assertEquals(true, DeviceQuirks.configFor(wiimDevice).dlnaPlayAfterSetUri)
        assertEquals(SpeakerConfig(), DeviceQuirks.configFor(DeviceIdentity("Google Cast", "Nest Mini", "Google Home Mini")))
    }

    @Test fun theFirstEntryThatSetsAFieldDecidesIt() {
        val entry = { id: String, config: SpeakerConfig ->
            KnownSpeaker(id, "x", "DLNA", Verdict.GOOD, Match("DLNA", listOf("box")), evidence = "e", config = config)
        }
        KnownSpeakersStore.setForTesting(
            KnownSpeakersFile(
                1, "", listOf(
                    entry("a", SpeakerConfig(defaultDelayMs = 300)),
                    entry("b", SpeakerConfig(defaultDelayMs = 900, sendMetadata = false)),
                )
            )
        )
        val config = DeviceQuirks.configFor(DeviceIdentity("DLNA", "Kitchen box"))
        assertEquals(300, config.defaultDelayMs)
        assertEquals(false, config.sendMetadata)
    }

    private val wiimDevice = DeviceIdentity("DLNA", "Den", "WiiM Pro Plus", "Linkplay")

    @Test fun anEchoIsListedAsNotSupported() {
        assertEquals(Verdict.UNSUPPORTED, DeviceQuirks.matching(DeviceIdentity(null, "Echo Show 5 kitchen")).single().verdict)
    }

    @Test fun ordinarySpeakersMatchNothing() {
        assertTrue(ids(DeviceIdentity("Google Cast", "Nest Mini", "Google Home Mini")).isEmpty())
        assertTrue(ids(DeviceIdentity("AirPlay2", "HomePod", "AudioAccessory5,1")).isEmpty())
    }

    @Test fun readsIdentityFromDiscoveryExtraFields() {
        val extra = ExtraFields.join(listOf("av_control" to "http://x/a;b", "manufacturer" to "Linkplay", "model" to "WiiM Pro"))
        val d = DeviceIdentity.of("DLNA", "Den", extra)
        assertEquals("Linkplay", d.manufacturer)
        assertEquals("WiiM Pro", d.model)
    }

    @Test fun aHostileModelCannotInjectFields() {
        val extra = ExtraFields.join(listOf("model" to "x;manufacturer=Evil"))
        val d = DeviceIdentity.of("DLNA", "Den", extra)
        assertEquals("x;manufacturer=Evil", d.model)
        assertEquals(null, d.manufacturer)
    }

    @Test fun reportLineListsWhatIsKnown() {
        assertEquals("protocol=DLNA, manufacturer=Linkplay, model=WiiM Pro, name=Den",
            DeviceIdentity("DLNA", "Den", "WiiM Pro", "Linkplay").reportLine())
        assertEquals("name=Den", DeviceIdentity(null, "Den").reportLine())
    }
}
