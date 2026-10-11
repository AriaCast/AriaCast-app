package com.aria.ariacast.cast

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

class CastMessageTest {
    @Test
    fun `round trips string and binary payloads`() {
        val m = CastMessage("sender-1", "receiver-0", CastClient.NS_RECEIVER, payloadUtf8 = """{"type":"GET_STATUS","requestId":7}""")
        assertEquals(m, CastMessage.decode(m.encode()))
        val b = CastMessage("a", "b", "urn:x-cast:com.google.cast.tp.deviceauth", payloadBinary = byteArrayOf(1, 2, 3))
        val d = CastMessage.decode(b.encode())
        assertArrayEquals(byteArrayOf(1, 2, 3), d.payloadBinary)
        assertEquals("urn:x-cast:com.google.cast.tp.deviceauth", d.namespace)
    }

    @Test
    fun `encoding matches the protobuf wire format`() {
        val bytes = CastMessage("s", "d", "n", payloadUtf8 = "p").encode()
        // field1 varint 0 | field2 "s" | field3 "d" | field4 "n" | field5 varint 0 | field6 "p"
        assertArrayEquals(byteArrayOf(0x08, 0x00, 0x12, 0x01, 's'.code.toByte(), 0x1A, 0x01, 'd'.code.toByte(),
            0x22, 0x01, 'n'.code.toByte(), 0x28, 0x00, 0x32, 0x01, 'p'.code.toByte()), bytes)
    }
}

/** Drives [CastClient] against an in-process CastV2 receiver over real TLS. */
class CastClientTest {
    private lateinit var server: FakeCastReceiver

    @Before
    fun setUp() { server = FakeCastReceiver().also { it.start() } }

    @After
    fun tearDown() { server.stop() }

    @Test
    fun `launches the default media receiver and loads the live stream with metadata`() {
        val client = CastClient("127.0.0.1", server.port)
        client.connect()
        client.launch()
        client.load("http://192.168.1.2:8091/stream.wav", "audio/wav", CastMetadata("Song", "Band", "Record", "http://192.168.1.2:8090/a.jpg"))

        assertEquals("web-1", client.transportId)
        assertEquals(1, client.mediaSessionId)
        val launch = server.received.first { it.second.str("type") == "LAUNCH" }
        assertEquals(CastClient.DEFAULT_MEDIA_RECEIVER, launch.second.str("appId"))
        val load = server.received.first { it.second.str("type") == "LOAD" }
        assertEquals("web-1", load.first.destinationId)
        val media = load.second["media"]!!.jsonObject
        assertEquals("http://192.168.1.2:8091/stream.wav", media.str("contentId"))
        assertEquals("audio/wav", media.str("contentType"))
        assertEquals("LIVE", media.str("streamType"))
        val meta = media["metadata"]!!.jsonObject
        assertEquals(3, meta["metadataType"]!!.jsonPrimitive.int)
        assertEquals("Song", meta.str("title"))
        assertEquals("Band", meta.str("artist"))
        assertEquals("Record", meta.str("albumName"))
        assertEquals("http://192.168.1.2:8090/a.jpg", meta["images"]!!.jsonArray[0].jsonObject.str("url"))
        // a virtual connection must be opened to the app's transport before talking to it
        assertTrue(server.received.any { it.first.destinationId == "web-1" && it.second.str("type") == "CONNECT" })

        client.updateMetadata(CastMetadata("Next Song", "Band", "Record", null))
        val update = server.waitFor("QUEUE_UPDATE")
        assertNotNull(update)
        val item = update!!.second["items"]!!.jsonArray[0].jsonObject
        assertEquals(1, item["itemId"]!!.jsonPrimitive.int)
        assertEquals("Next Song", item["media"]!!.jsonObject["metadata"]!!.jsonObject.str("title"))

        client.setVolume(0.25)
        val vol = server.waitFor("SET_VOLUME")!!
        assertEquals(0.25, vol.second["volume"]!!.jsonObject["level"]!!.jsonPrimitive.double, 1e-9)

        client.stop()
        assertNotNull(server.waitFor("STOP"))
        client.close()
    }

    @Test
    fun `joins an already running receiver app instead of relaunching it`() {
        server.appRunning = true
        val client = CastClient("127.0.0.1", server.port)
        client.connect()
        client.launch()
        assertEquals("web-1", client.transportId)
        assertTrue(server.received.none { it.second.str("type") == "LAUNCH" })
        client.close()
    }

    @Test
    fun `reports device side volume, pause and app takeover`() {
        val volume = CountDownLatch(1)
        val paused = CountDownLatch(1)
        val gone = CountDownLatch(1)
        var level = -1.0
        val client = CastClient("127.0.0.1", server.port, object : CastListener {
            override fun onVolume(level0: Double, muted: Boolean) { level = level0; if (level0 == 0.8) volume.countDown() }
            override fun onRemoteCommand(command: String) { if (command == "pause") paused.countDown() }
            override fun onDisconnected(reason: String) { if (reason == "receiver app stopped") gone.countDown() }
        })
        client.connect()
        client.launch()
        client.load("http://x/stream.wav", "audio/wav", null)

        server.pushReceiverStatus(appRunning = true, level = 0.8)
        assertTrue(volume.await(3, TimeUnit.SECONDS))
        assertEquals(0.8, level, 1e-9)

        server.pushMediaStatus("PAUSED")
        assertTrue("pause from the device reaches the phone", paused.await(3, TimeUnit.SECONDS))

        server.pushReceiverStatus(appRunning = false, level = 0.8)
        assertTrue("another app taking over ends the session", gone.await(3, TimeUnit.SECONDS))
        client.close()
    }

    @Test
    fun `answers the receiver's heartbeat pings`() {
        val client = CastClient("127.0.0.1", server.port)
        client.connect()
        server.ping()
        assertNotNull(server.waitFor("PONG"))
        client.close()
    }
}

private fun JsonObject.str(k: String): String? = this[k]?.jsonPrimitive?.content

/** A minimal CastV2 receiver: TLS, framing, receiver + media namespaces. */
class FakeCastReceiver {
    private val ssl: SSLServerSocket
    val port: Int get() = ssl.localPort
    val received = CopyOnWriteArrayList<Pair<CastMessage, JsonObject>>()
    @Volatile var appRunning = false
    private var out: DataOutputStream? = null
    private val json = Json { ignoreUnknownKeys = true }
    private var sender = "sender-0"

    init {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=fake-cast")
        val cert = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(System.currentTimeMillis() - 60_000),
                Date(System.currentTimeMillis() + 86_400_000), name, kp.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private)))
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null); setKeyEntry("k", kp.private, "pw".toCharArray(), arrayOf(cert)) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "pw".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        ssl = ctx.serverSocketFactory.createServerSocket(0, 5, InetAddress.getLoopbackAddress()) as SSLServerSocket
    }

    fun start() {
        thread(isDaemon = true) {
            try {
                val s = ssl.accept()
                val input = DataInputStream(s.getInputStream())
                out = DataOutputStream(s.getOutputStream())
                while (true) {
                    val len = input.readInt()
                    val buf = ByteArray(len).also { input.readFully(it) }
                    val msg = CastMessage.decode(buf)
                    val obj = json.parseToJsonElement(msg.payloadUtf8 ?: "{}").jsonObject
                    received.add(msg to obj)
                    sender = msg.sourceId
                    handle(msg, obj)
                }
            } catch (_: Exception) {}
        }
    }

    fun stop() { try { ssl.close() } catch (_: Exception) {} }

    fun waitFor(type: String, timeoutMs: Long = 3000): Pair<CastMessage, JsonObject>? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            received.lastOrNull { it.second.str("type") == type }?.let { return it }
            Thread.sleep(20)
        }
        return null
    }

    private fun send(from: String, ns: String, payload: JsonObject) {
        val bytes = CastMessage(from, sender, ns, payloadUtf8 = payload.toString()).encode()
        synchronized(this) { out?.writeInt(bytes.size); out?.write(bytes); out?.flush() }
    }

    private fun receiverStatus(requestId: Int, level: Double = 0.4) = buildJsonObject {
        put("type", "RECEIVER_STATUS"); put("requestId", requestId)
        put("status", buildJsonObject {
            put("applications", buildJsonArray {
                if (appRunning) add(buildJsonObject {
                    put("appId", CastClient.DEFAULT_MEDIA_RECEIVER); put("sessionId", "sess-1"); put("transportId", "web-1")
                })
            })
            put("volume", buildJsonObject { put("level", level); put("muted", false) })
        })
    }

    fun pushReceiverStatus(appRunning: Boolean, level: Double) {
        this.appRunning = appRunning
        send(CastClient.RECEIVER_ID, CastClient.NS_RECEIVER, receiverStatus(0, level))
    }

    fun pushMediaStatus(state: String) = send("web-1", CastClient.NS_MEDIA, mediaStatus(0, state))

    fun ping() = send(CastClient.RECEIVER_ID, CastClient.NS_HEARTBEAT, buildJsonObject { put("type", "PING") })

    private fun mediaStatus(requestId: Int, state: String) = buildJsonObject {
        put("type", "MEDIA_STATUS"); put("requestId", requestId)
        put("status", buildJsonArray { add(buildJsonObject {
            put("mediaSessionId", 1); put("playerState", state); put("currentItemId", 1)
        }) })
    }

    private fun handle(msg: CastMessage, obj: JsonObject) {
        val id = obj["requestId"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        when (msg.namespace) {
            CastClient.NS_HEARTBEAT -> if (obj.str("type") == "PING") send(msg.destinationId, CastClient.NS_HEARTBEAT, buildJsonObject { put("type", "PONG") })
            CastClient.NS_RECEIVER -> when (obj.str("type")) {
                "GET_STATUS" -> send(CastClient.RECEIVER_ID, CastClient.NS_RECEIVER, receiverStatus(id))
                "LAUNCH" -> { appRunning = true; send(CastClient.RECEIVER_ID, CastClient.NS_RECEIVER, receiverStatus(id)) }
                "SET_VOLUME" -> send(CastClient.RECEIVER_ID, CastClient.NS_RECEIVER, receiverStatus(id))
                "STOP" -> { appRunning = false; send(CastClient.RECEIVER_ID, CastClient.NS_RECEIVER, receiverStatus(id)) }
            }
            CastClient.NS_MEDIA -> when (obj.str("type")) {
                "LOAD" -> send("web-1", CastClient.NS_MEDIA, mediaStatus(id, "PLAYING"))
                "QUEUE_UPDATE", "PLAY", "PAUSE" -> send("web-1", CastClient.NS_MEDIA, mediaStatus(id, if (obj.str("type") == "PAUSE") "PAUSED" else "PLAYING"))
            }
        }
    }
}
