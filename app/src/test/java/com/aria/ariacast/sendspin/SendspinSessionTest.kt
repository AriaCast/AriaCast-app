package com.aria.ariacast.sendspin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Drives a SendspinSession against a minimal fake Sendspin server (the Noise initiator),
 * through the full Pairing PSK flow, both re-handshakes, activation, clock sync, client
 * state, a player stream and a source stream.
 */
class SendspinSessionTest {
    private class MemStore : SendspinStore {
        override val clientKeys = Noise.KeyPair.generate()
        override val pairingPsk = ByteArray(32) { (it * 7).toByte() }
        override var unpairedAccess = false
        val recs = mutableMapOf<String, ByteArray>()
        override fun longTermPsk(serverId: String) = recs[serverId]
        override fun records() = recs.map { it.key to it.value }
        override fun saveRecord(serverId: String, serverName: String, psk: ByteArray) { recs[serverId] = psk }
        override fun removeRecord(serverId: String) { recs.remove(serverId) }
        override var lastPlaybackServer: String? = null
        override var volume = 40
        override var muted = false
    }

    /** Fake server: holds the server side of the Noise channel. */
    private class FakeServer(val clientPub: ByteArray) {
        val keys = Noise.KeyPair.generate()
        val id = SendspinCodec.b64url(keys.public)
        var send: Noise.CipherState? = null
        var recv: Noise.CipherState? = null
        var h: ByteArray? = null
        var pendingHs: Noise.KKpsk2Handshake? = null
        val received = mutableListOf<JsonObject>()
        val binaries = mutableListOf<ByteArray>()

        fun decrypt(bytes: ByteArray): ByteArray = recv!!.decryptWithAd(ByteArray(0), bytes)
        fun json(type: String, payload: JsonObject): ByteArray =
            send!!.encryptWithAd(ByteArray(0), byteArrayOf(0) + buildJsonObject { put("type", type); put("payload", payload) }.toString().toByteArray())

        fun noise1(prologue: ByteArray, psk: ByteArray, category: String): ByteArray {
            val hs = Noise.KKpsk2Handshake(true, keys, clientPub, prologue).also { it.psk = psk }
            pendingHs = hs
            return hs.writeMessage1(buildJsonObject { put("psk_id", SendspinCodec.pskId(psk)); put("psk_category", category) }.toString().toByteArray())
        }

        fun finish(msg2: ByteArray) {
            val (_, t) = pendingHs!!.readMessage2(msg2)
            send = t.send; recv = t.receive; h = t.handshakeHash
        }
    }

    @Test fun pairingPskFlowThenPlaybackAndSource() {
        val store = MemStore()
        val texts = mutableListOf<String>()
        val bins = mutableListOf<ByteArray>()
        var closed: String? = null
        val audio = mutableListOf<Pair<Long, ByteArray>>()
        var paired = false
        var sourceStarted = false
        var clock = 1_000_000L
        val session = SendspinSession(
            store,
            SendspinSession.Config("Test phone", "Pixel", "Google", "2.0"),
            object : SendspinSession.Io {
                override fun sendText(text: String) { texts += text }
                override fun sendBinary(bytes: ByteArray) { bins += bytes }
                override fun close() {}
            },
            object : SendspinSession.Listener {
                override fun onPlayerAudio(localPlayUs: Long, pcm: ByteArray) { audio += localPlayUs to pcm }
                override fun onPaired(serverName: String) { paired = true }
                override fun onSourceStart(): Boolean { sourceStarted = true; return true }
                override fun onClosed(reason: String) { closed = reason }
            },
            clockUs = { clock },
        )
        val server = FakeServer(store.clientKeys.public)
        fun takeJson(): JsonObject = Json.parseToJsonElement(String(server.decrypt(bins.removeAt(0)).let { it.copyOfRange(1, it.size) })).jsonObject

        // client/init (cleartext)
        session.start()
        val clientInit = texts.removeAt(0)
        assertEquals("client/init", Json.parseToJsonElement(clientInit).jsonObject["type"]!!.jsonPrimitive.content)
        val serverInit = buildJsonObject { put("type", "server/init"); putJsonObject("payload") { put("server_id", server.id); put("version", 1) } }.toString()
        session.onText(serverInit)

        // Initial handshake with the Sentinel PSK
        val m1 = server.noise1(clientInit.toByteArray() + serverInit.toByteArray(), SendspinCodec.SENTINEL_PSK, "sn")
        session.onText(buildJsonObject { put("type", "noise/handshake"); putJsonObject("payload") { put("data", SendspinCodec.b64url(m1)) } }.toString())
        val m2 = SendspinCodec.unb64url(Json.parseToJsonElement(texts.removeAt(0)).jsonObject["payload"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        server.finish(m2)
        assertEquals("sn", session.matchedCategory)

        // hello exchange
        session.onBinary(server.json("server/hello", buildJsonObject { put("name", "Music Assistant") }))
        val hello = takeJson()
        assertEquals("client/hello", hello["type"]!!.jsonPrimitive.content)
        assertTrue(hello["payload"]!!.jsonObject["supported_pair_methods"]!!.jsonObject.containsKey("pairing_psk"))
        // Servers don't activate a listed role whose support object is missing.
        for (role in listOf("player@v1", "source@v1")) assertTrue(role, hello["payload"]!!.jsonObject.containsKey("${role}_support"))

        // Operator entered our pairing token: re-handshake to the pairing PSK (prologue = previous h)
        fun rehandshake(psk: ByteArray, category: String) {
            val r1 = server.noise1(server.h!!, psk, category)
            session.onBinary(server.json("noise/handshake", buildJsonObject { put("data", SendspinCodec.b64url(r1)) }))
            val r2 = takeJson() // still under the old keys
            assertEquals("noise/handshake", r2["type"]!!.jsonPrimitive.content)
            server.finish(SendspinCodec.unb64url(r2["payload"]!!.jsonObject["data"]!!.jsonPrimitive.content))
        }
        rehandshake(store.pairingPsk, "pr")
        assertEquals("pr", session.matchedCategory)

        session.onBinary(server.json("server/activate", buildJsonObject {
            putJsonArray("activities") { add(JsonPrimitive("pairing")) }
            putJsonArray("active_roles") {}
            putJsonObject("pairing") { put("method", "pairing_psk") }
        }))
        // client/time burst starts on first activation; the reply comes later
        val firstTime = takeJson()
        assertEquals("client/time", firstTime["type"]!!.jsonPrimitive.content)
        val pairInit = takeJson()
        assertEquals("client/pair-init", pairInit["type"]!!.jsonPrimitive.content)
        val finalize = takeJson()
        val lt = SendspinCodec.unb64url(finalize["payload"]!!.jsonObject["long_term_psk"]!!.jsonPrimitive.content)
        session.onBinary(server.json("server/pair-finalize", buildJsonObject {}))
        assertTrue(paired)
        assertArrayEquals(lt, store.recs[server.id])

        // Promote to a paired session
        rehandshake(lt, "lt")
        assertEquals("lt", session.matchedCategory)
        session.onBinary(server.json("server/activate", buildJsonObject {
            putJsonArray("activities") { add(JsonPrimitive("playback")) }
            putJsonArray("active_roles") { add(JsonPrimitive("player@v1")); add(JsonPrimitive("source@v1")) }
        }))
        assertEquals(setOf("player@v1", "source@v1"), session.activeRoles)

        // Clock sync: answer the outstanding client/time and the rest of the burst (server = client + 5 s)
        repeat(8) { i ->
            val t = if (i == 0) firstTime else generateSequence { if (bins.isEmpty()) null else takeJson() }.first { it["type"]!!.jsonPrimitive.content == "client/time" }
            val t1 = t["payload"]!!.jsonObject["client_transmitted"]!!.jsonPrimitive.long
            clock += 1000
            session.onBinary(server.json("server/time", buildJsonObject {
                put("client_transmitted", t1); put("server_received", t1 + 5_000_500); put("server_transmitted", t1 + 5_000_600)
            }))
        }
        val state = generateSequence { if (bins.isEmpty()) null else takeJson() }.first { it["type"]!!.jsonPrimitive.content == "client/state" }
        assertEquals(40, state["payload"]!!.jsonObject["player"]!!.jsonObject["volume"]!!.jsonPrimitive.long.toInt())

        // Player stream: one 10 ms chunk
        session.onBinary(server.json("stream/start", buildJsonObject {
            put("server_transmitted", 0)
            putJsonObject("player") { put("codec", "pcm"); put("sample_rate", 48000); put("channels", 2); put("bit_depth", 16) }
        }))
        val serverTs = clock + 5_000_000 + 300_000
        val chunk = ByteBuffer.allocate(13 + 1920).put(4).putLong(serverTs).putInt(300_000).array()
        session.onBinary(server.send!!.encryptWithAd(ByteArray(0), chunk))
        assertEquals(1, audio.size)
        assertTrue("local play time ${audio[0].first}", kotlin.math.abs(audio[0].first - (clock + 300_000)) < 2_000)

        // Source: Music Assistant activates a capture device with no activities, only the role.
        session.onBinary(server.json("server/activate", buildJsonObject {
            putJsonArray("activities") {}
            putJsonArray("active_roles") { add(JsonPrimitive("source@v1")) }
        }))
        assertEquals(setOf("source@v1"), session.activeRoles)
        // Signal present, server starts it, client-stream/start, audio flows as binary type 12
        session.setSourceSignal(true)
        session.onBinary(server.json("server/command", buildJsonObject { putJsonObject("source") { put("command", "start") } }))
        assertTrue(sourceStarted)
        session.sendSourceAudio(clock, ByteArray(3840))
        val msgs = bins.map { server.decrypt(it) } // once each: the receive nonce advances
        assertTrue(msgs.any { it[0].toInt() == 0 && String(it, 1, it.size - 1).contains("\"client-stream/start\"") })
        val types = msgs.map { it[0].toInt() }
        assertTrue("got $types", 12 in types)
        assertEquals(null, closed)
    }

    @Test fun unpairedPlaybackIsRefusedWhenUnpairedAccessIsOff() {
        val store = MemStore()
        val texts = mutableListOf<String>(); val bins = mutableListOf<ByteArray>()
        var closed: String? = null
        val session = SendspinSession(store, SendspinSession.Config("T", null, null, null), object : SendspinSession.Io {
            override fun sendText(text: String) { texts += text }
            override fun sendBinary(bytes: ByteArray) { bins += bytes }
            override fun close() {}
        }, object : SendspinSession.Listener { override fun onClosed(reason: String) { closed = reason } })
        val server = FakeServer(store.clientKeys.public)
        session.start()
        val ci = texts.removeAt(0)
        val si = buildJsonObject { put("type", "server/init"); putJsonObject("payload") { put("server_id", server.id); put("version", 1) } }.toString()
        session.onText(si)
        session.onText(buildJsonObject { put("type", "noise/handshake"); putJsonObject("payload") {
            put("data", SendspinCodec.b64url(server.noise1(ci.toByteArray() + si.toByteArray(), SendspinCodec.SENTINEL_PSK, "sn"))) } }.toString())
        server.finish(SendspinCodec.unb64url(Json.parseToJsonElement(texts.removeAt(0)).jsonObject["payload"]!!.jsonObject["data"]!!.jsonPrimitive.content))
        session.onBinary(server.json("server/activate", buildJsonObject {
            putJsonArray("activities") { add(JsonPrimitive("playback")) }
            putJsonArray("active_roles") { add(JsonPrimitive("player@v1")) }
        }))
        assertEquals("pairing_required", closed)
        val last = Json.parseToJsonElement(String(server.decrypt(bins.last()).let { it.copyOfRange(1, it.size) })).jsonObject
        assertEquals("client/goodbye", last["type"]!!.jsonPrimitive.content)
    }
}
