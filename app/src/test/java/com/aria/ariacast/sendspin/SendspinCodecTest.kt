package com.aria.ariacast.sendspin

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SendspinCodecTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun sentinelConstantsMatchSpec() {
        assertEquals("1b5e24dbc1aed95fc2a5a338a90c05df44bd10f5ec1f4cd66cbf86272767b9d3", hex(SendspinCodec.SENTINEL_PSK))
        assertEquals("GFsV9tLaSQm9HcFWpKsgYQOr7wFTvNUtkmFwuVz3zoo", SendspinCodec.pskId(SendspinCodec.SENTINEL_PSK))
    }

    @Test fun pairingPskTokenMatchesReferenceVector() {
        val key = ByteArray(32) { it.toByte() }
        val psk = ByteArray(32) { (0xe0 + it).toByte() }
        val token = SendspinCodec.pairingPskToken(key, psk)
        assertEquals("SP:0AAAQEAYEAUDAOCAJBIFQYDIOB4IBCEQTCQKRMFYYDENBWHA5DYP6BYPC4PSOLZXH5DU6V97M5XXO74HR6LZ7J5PW674PT6X37T6757Y", token)
        val (v, payload) = SendspinCodec.decodeToken(" sp:" + token.removePrefix("SP:").lowercase() + "\n")
        assertEquals('0', v)
        assertArrayEquals(key + psk, payload)
    }

    @Test fun qrCodeTokenMatchesReferenceVector() {
        val code = ByteArray(24) { (0xe0 + it).toByte() }
        assertEquals("SP:14DQ6FY7E4XTOP9HJ5LV6Z3PO57YPD4XT6T97N5Y", SendspinCodec.pairingToken('1', code))
    }

    @Test fun idsAre43CharBase64Url() {
        val id = SendspinCodec.b64url(ByteArray(32) { 7 })
        assertEquals(43, id.length)
        assertArrayEquals(ByteArray(32) { 7 }, SendspinCodec.decodeId(id))
        assertEquals(null, SendspinCodec.decodeId("too-short"))
    }

    @Test fun timeFilterConvergesOnOffsetAndDrift() {
        val f = TimeFilter()
        // Server clock = client + 1_000_000 us, drifting +20 ppm; 2 ms round trips.
        var t = 1_000_000L
        repeat(300) {
            val t1 = t
            val serverAt = { c: Long -> c + 1_000_000 + ((c - 1_000_000) * 20e-6).toLong() }
            val t2 = serverAt(t1 + 1000)
            val t3 = t2 + 50
            val t4 = t1 + 2050
            f.update(t1, t2, t3, t4)
            t += 1_000_000
        }
        val now = t
        val expectedServer = now + 1_000_000 + ((now - 1_000_000) * 20e-6).toLong()
        assertTrue("server time off by ${f.serverTime(now) - expectedServer}", abs(f.serverTime(now) - expectedServer) < 500)
        assertTrue(abs(f.clientTime(f.serverTime(now)) - now) <= 1)
    }
}
