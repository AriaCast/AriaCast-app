package com.aria.ariacast.sendspin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Checks the KKpsk2 implementation byte-for-byte against the cacophony test vector. */
class NoiseTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private val v by lazy {
        val f = listOf("src/test/resources/noise/kkpsk2_25519_chachapoly_sha256.json", "app/src/test/resources/noise/kkpsk2_25519_chachapoly_sha256.json")
            .map(::File).first { it.exists() }
        Json.parseToJsonElement(f.readText()).jsonObject
    }
    private fun JsonObject.getString(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.getJSONArray(k: String) = this[k]!!.jsonArray
    private fun kotlinx.serialization.json.JsonArray.getString(i: Int) = this[i].jsonPrimitive.content
    private fun kotlinx.serialization.json.JsonArray.getJSONObject(i: Int) = this[i].jsonObject
    private fun kotlinx.serialization.json.JsonArray.length() = size

    @Test fun handshakeAndTransportMatchVector() {
        val psk = hex(v.getJSONArray("init_psks").getString(0))
        val init = Noise.KKpsk2Handshake(true, Noise.KeyPair.fromPrivate(hex(v.getString("init_static"))),
            hex(v.getString("init_remote_static")), hex(v.getString("init_prologue")), Noise.KeyPair.fromPrivate(hex(v.getString("init_ephemeral"))))
        val resp = Noise.KKpsk2Handshake(false, Noise.KeyPair.fromPrivate(hex(v.getString("resp_static"))),
            hex(v.getString("resp_remote_static")), hex(v.getString("resp_prologue")), Noise.KeyPair.fromPrivate(hex(v.getString("resp_ephemeral"))))
        init.psk = psk
        resp.psk = psk
        val msgs = v.getJSONArray("messages")

        val m1 = init.writeMessage1(hex(msgs.getJSONObject(0).getString("payload")))
        assertEquals(msgs.getJSONObject(0).getString("ciphertext"), m1.hex())
        assertArrayEquals(hex(msgs.getJSONObject(0).getString("payload")), resp.readMessage1(m1))

        val (m2, respT) = resp.writeMessage2(hex(msgs.getJSONObject(1).getString("payload")))
        assertEquals(msgs.getJSONObject(1).getString("ciphertext"), m2.hex())
        val (p2, initT) = init.readMessage2(m2)
        assertArrayEquals(hex(msgs.getJSONObject(1).getString("payload")), p2)

        assertEquals(v.getString("handshake_hash"), initT.handshakeHash.hex())
        assertEquals(v.getString("handshake_hash"), respT.handshakeHash.hex())

        // Transport messages alternate initiator -> responder -> initiator ...
        for (i in 2 until msgs.length()) {
            val m = msgs.getJSONObject(i)
            val (sender, receiver) = if (i % 2 == 0) initT to respT else respT to initT
            val ct = sender.send.encryptWithAd(ByteArray(0), hex(m.getString("payload")))
            assertEquals("message $i", m.getString("ciphertext"), ct.hex())
            assertArrayEquals(hex(m.getString("payload")), receiver.receive.decryptWithAd(ByteArray(0), ct))
        }
    }

    @Test(expected = Exception::class)
    fun tamperedMessageFailsAuthentication() {
        val a = Noise.KeyPair.generate(); val b = Noise.KeyPair.generate()
        val init = Noise.KKpsk2Handshake(true, a, b.public, ByteArray(0))
        val resp = Noise.KKpsk2Handshake(false, b, a.public, ByteArray(0))
        val m1 = init.writeMessage1("{}".toByteArray())
        m1[m1.size - 1] = (m1[m1.size - 1].toInt() xor 1).toByte()
        resp.readMessage1(m1)
    }

    @Test fun wrongPskIsDetectedByTheInitiator() {
        val a = Noise.KeyPair.generate(); val b = Noise.KeyPair.generate()
        val init = Noise.KKpsk2Handshake(true, a, b.public, ByteArray(0)).apply { psk = ByteArray(32) { 1 } }
        val resp = Noise.KKpsk2Handshake(false, b, a.public, ByteArray(0)).apply { psk = ByteArray(32) { 2 } }
        resp.readMessage1(init.writeMessage1("{}".toByteArray()))
        val (m2, _) = resp.writeMessage2("{}".toByteArray())
        val failed = runCatching { init.readMessage2(m2) }.isFailure
        assertEquals(true, failed)
    }
}
