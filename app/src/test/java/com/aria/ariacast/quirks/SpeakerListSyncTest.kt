package com.aria.ariacast.quirks

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SpeakerListSyncTest {
    private val servers = mutableListOf<ServerSocket>()

    @After fun stop() = servers.forEach { runCatching { it.close() } }

    /** A one-request-at-a-time HTTP server on a plain socket; [respond] writes the raw reply. */
    private fun serve(respond: (OutputStream) -> Unit): String {
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        servers += socket
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { client ->
                        val input = client.getInputStream().bufferedReader()
                        while (input.readLine().let { it != null && it.isNotEmpty() }) { /* skip the request */ }
                        runCatching { respond(client.getOutputStream()) }
                    }
                } catch (_: IOException) {
                }
            }
        }
        return "http://127.0.0.1:${socket.localPort}/speakers.json"
    }

    private fun OutputStream.raw(text: String) = write(text.toByteArray())

    private fun ok(body: String) = { out: OutputStream ->
        out.raw("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body")
    }

    private fun failsWith(block: () -> Unit): String {
        try { block() } catch (e: IOException) { return e.message ?: "" }
        fail("expected an IOException")
        return ""
    }

    @Test fun readsAFile() {
        val url = serve(ok("""{"schema":2}"""))
        assertEquals("""{"schema":2}""", SpeakerListSync.fetch(url, requireHttps = false))
    }

    @Test fun plainHttpIsRefusedUnlessAllowed() {
        val url = serve(ok("x"))
        assertEquals("not https", failsWith { SpeakerListSync.fetch(url) })
    }

    @Test fun anErrorStatusIsAFailure() {
        assertEquals("HTTP 404", failsWith { SpeakerListSync.fetch(serve { it.raw("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n") }, requireHttps = false) })
    }

    @Test fun redirectsAreNotFollowed() {
        val target = serve(ok("secret"))
        val url = serve { it.raw("HTTP/1.1 302 Found\r\nLocation: $target\r\nContent-Length: 0\r\nConnection: close\r\n\r\n") }
        assertEquals("HTTP 302", failsWith { SpeakerListSync.fetch(url, requireHttps = false) })
    }

    @Test fun aFileThatDeclaresItselfTooBigIsRefusedUpFront() {
        val url = serve { it.raw("HTTP/1.1 200 OK\r\nContent-Length: ${KnownSpeakersFile.MAX_BYTES + 1}\r\nConnection: close\r\n\r\nxxxx") }
        assertEquals("file too big", failsWith { SpeakerListSync.fetch(url, requireHttps = false) })
    }

    @Test fun aFileThatGrowsPastTheLimitWhileStreamingIsRefused() {
        // No length is announced, so only the running count can catch it.
        val url = serve {
            it.raw("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n")
            it.write(ByteArray(KnownSpeakersFile.MAX_BYTES + 100) { 'x'.code.toByte() })
        }
        assertEquals("file too big", failsWith { SpeakerListSync.fetch(url, requireHttps = false) })
    }

    @Test fun theLimitItselfIsAllowed() {
        assertEquals(5, SpeakerListSync.readBounded(ByteArrayInputStream("abcde".toByteArray()), 5).length)
        assertEquals("file too big", failsWith { SpeakerListSync.readBounded(ByteArrayInputStream("abcdef".toByteArray()), 5) })
    }

    @Test fun aBadAddressIsAFailureNotACrash() {
        assertEquals("bad address", failsWith { SpeakerListSync.fetch("not a url", requireHttps = false) })
    }

    private fun list(version: Int) = """{"schema":2,"version":$version,"speakers":[]}"""

    @Test fun onlyAValidNewerListIsTaken() {
        assertTrue(SpeakerListSync.evaluate(list(5), currentVersion = 4) is SpeakerListSync.Verdict.Newer)
        assertEquals(SpeakerListSync.Verdict.NotNewer(4), SpeakerListSync.evaluate(list(4), currentVersion = 4))
        assertEquals(SpeakerListSync.Verdict.NotNewer(3), SpeakerListSync.evaluate(list(3), currentVersion = 4))
        assertEquals(SpeakerListSync.Verdict.Invalid, SpeakerListSync.evaluate("<html>captive portal</html>", currentVersion = 4))
        assertEquals(SpeakerListSync.Verdict.Invalid, SpeakerListSync.evaluate("""{"schema":9,"version":99,"speakers":[]}""", currentVersion = 4))
    }

    @Test fun checksOncePerDay() {
        val hour = 60 * 60 * 1000L
        val now = 1_000_000_000_000L
        assertTrue(SpeakerListSync.isDue(0, now))
        assertFalse(SpeakerListSync.isDue(now - hour, now))
        assertFalse(SpeakerListSync.isDue(now - 23 * hour, now))
        assertTrue(SpeakerListSync.isDue(now - 25 * hour, now))
        assertTrue(SpeakerListSync.isDue(now + hour, now)) // the clock went backwards
    }
}
