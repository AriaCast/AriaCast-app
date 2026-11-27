package com.aria.ariacast.quirks

import com.aria.ariacast.ExtraFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceQuirksTest {
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

    @Test fun onlyEntriesWithAMessageReachTheDoctor() {
        assertEquals(listOf("wiim-dlna-restart"), DeviceQuirks.notesFor(wiimDevice).map { it.id })
        assertTrue(DeviceQuirks.notesFor(DeviceIdentity("DLNA", "Living", "Play:5", "Sonos, Inc.")).isEmpty())
    }

    private val wiimDevice = DeviceIdentity("DLNA", "Den", "WiiM Pro Plus", "Linkplay")

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
