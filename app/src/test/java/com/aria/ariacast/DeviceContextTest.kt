package com.aria.ariacast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeviceContextTest {
    @Before fun setUp() = PacketLogger.clear()

    private fun deviceOf(message: String) = PacketLogger.logs.first { it.message == message }.device

    @Test fun linesLoggedInsideASpeakersCoroutineAreAttributedToIt() = runBlocking {
        withContext(DeviceContext.element("10.0.0.1")) {
            PacketLogger.log(PacketDirection.OUT, PacketType.HANDSHAKE, "plain")
            withContext(Dispatchers.IO) { PacketLogger.log(PacketDirection.OUT, PacketType.HANDSHAKE, "on another thread") }
            coroutineScope { launch { PacketLogger.log(PacketDirection.OUT, PacketType.HANDSHAKE, "in a child") } }
        }
        assertEquals("10.0.0.1", deviceOf("plain"))
        assertEquals("10.0.0.1", deviceOf("on another thread"))
        assertEquals("10.0.0.1", deviceOf("in a child"))
    }

    @Test fun linesOutsideAnySpeakerStayUntagged() = runBlocking {
        PacketLogger.log(PacketDirection.OUT, PacketType.HANDSHAKE, "outside")
        withContext(DeviceContext.element("10.0.0.1")) { }
        PacketLogger.log(PacketDirection.OUT, PacketType.HANDSHAKE, "after")
        assertNull(deviceOf("outside"))
        assertNull(deviceOf("after")) // the context does not leak onto the thread once the block ends
    }

    @Test fun anExplicitHostWins() = runBlocking {
        withContext(DeviceContext.element("10.0.0.1")) {
            PacketLogger.log(PacketDirection.IN, PacketType.HANDSHAKE, "explicit", device = "10.0.0.9")
        }
        assertEquals("10.0.0.9", deviceOf("explicit"))
    }

    @Test fun concurrentSpeakersDoNotMixTheirLines() = runBlocking {
        // The messages must not name the host, or the text-matching fallback would find them
        // without any tag and this would prove nothing.
        val hosts = (1..8).map { "10.0.0.$it" }
        hosts.mapIndexed { index, host ->
            async(Dispatchers.IO + DeviceContext.element(host)) {
                repeat(50) { PacketLogger.log(PacketDirection.OUT, PacketType.AUDIO, "speaker $index line $it") }
            }
        }.awaitAll()
        hosts.forEachIndexed { index, host ->
            val lines = PacketLogger.logsFor(host, "")
            assertEquals(50, lines.size)
            assertTrue(lines.all { it.message.startsWith("speaker $index line ") })
        }
    }
}
