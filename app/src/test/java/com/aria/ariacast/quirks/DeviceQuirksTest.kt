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

    @Test fun matchesShairportOnAirPlay2Only() {
        assertEquals(listOf("shairport-airplay2-ptp"), ids(DeviceIdentity("AirPlay2", "Kitchen", "ShairportSync")))
        assertTrue(ids(DeviceIdentity("AirPlay", "Kitchen", "ShairportSync")).isEmpty())
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
