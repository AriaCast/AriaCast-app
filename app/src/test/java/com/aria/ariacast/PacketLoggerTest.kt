package com.aria.ariacast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * AudioCastService launches a separate control/audio/stats coroutine per cast
 * destination (all on Dispatchers.IO), and every one of them calls
 * PacketLogger.log() on its own thread. This test reproduces that concurrency
 * to guard against regressions like a shared non-thread-safe SimpleDateFormat.
 */
class PacketLoggerTest {

    @Before
    fun setUp() {
        PacketLogger.clear()
    }

    @Test
    fun `log() survives concurrent calls from many threads without throwing or corrupting timestamps`() {
        val threadCount = 16
        val logsPerThread = 500
        val timestampPattern = Regex("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3}$")

        val errors = CopyOnWriteArrayList<Throwable>()
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)

        repeat(threadCount) { threadIndex ->
            executor.submit {
                try {
                    startLatch.await()
                    repeat(logsPerThread) { i ->
                        // Unique message per call so entries aren't coalesced by the
                        // repeat-collapsing logic, keeping the formatter under load.
                        PacketLogger.log(
                            direction = if (i % 2 == 0) PacketDirection.IN else PacketDirection.OUT,
                            type = PacketType.AUDIO,
                            message = "thread-$threadIndex-frame-$i"
                        )
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        assertTrue("Threads did not finish in time", doneLatch.await(30, TimeUnit.SECONDS))
        executor.shutdown()

        assertTrue("PacketLogger.log() threw under concurrent access: $errors", errors.isEmpty())

        val logs = PacketLogger.logs
        assertTrue("Expected some logs to be recorded", logs.isNotEmpty())
        assertTrue("Log buffer should be capped at 500 entries", logs.size <= 500)
        logs.forEach { entry ->
            assertTrue(
                "Timestamp \"${entry.timestamp}\" is not well-formed (SimpleDateFormat corruption?)",
                timestampPattern.matches(entry.timestamp)
            )
        }
    }

    @Test
    fun `log() coalesces identical consecutive entries into a single entry with an incrementing count`() {
        repeat(5) {
            PacketLogger.log(PacketDirection.OUT, PacketType.AUDIO, "Audio frame sent")
        }

        val logs = PacketLogger.logs
        assertEquals(1, logs.size)
        assertEquals(5, logs[0].count)
    }

    @Test
    fun `the same message about two speakers is two lines, and the tag is kept`() {
        PacketLogger.log(PacketDirection.OUT, PacketType.HANDSHAKE, "connected", device = "10.0.0.1")
        PacketLogger.log(PacketDirection.OUT, PacketType.HANDSHAKE, "connected", device = "10.0.0.2")
        PacketLogger.log(PacketDirection.OUT, PacketType.HANDSHAKE, "connected", device = "10.0.0.2")
        val logs = PacketLogger.logs
        assertEquals(2, logs.size)
        assertEquals("10.0.0.2", logs[0].device)
        assertEquals(2, logs[0].count)
        assertEquals("10.0.0.1", logs[1].device)
    }

    private fun log(message: String, device: String?) =
        PacketLogger.log(PacketDirection.OUT, PacketType.HANDSHAKE, message, device = device)

    @Test
    fun `a chatty speaker cannot push another speaker's history out`() {
        log("quiet speaker: connected", "10.0.0.1")
        repeat(PacketLogger.GLOBAL_LIMIT + 200) { log("noise $it", "10.0.0.2") }
        assertTrue(PacketLogger.logs.none { it.message == "quiet speaker: connected" })
        assertEquals(listOf("quiet speaker: connected"), PacketLogger.logsFor("10.0.0.1", "").map { it.message })
        assertEquals(PacketLogger.DEVICE_LIMIT, PacketLogger.logsFor("10.0.0.2", "").size)
    }

    @Test
    fun `repeats are merged per speaker even when other speakers log in between`() {
        repeat(3) {
            log("tick", "10.0.0.1")
            log("tock", "10.0.0.2")
        }
        val a = PacketLogger.logsFor("10.0.0.1", "")
        assertEquals(1, a.size)
        assertEquals(3, a[0].count)
    }

    @Test
    fun `logsFor adds untagged lines that name the speaker, in the order they were logged`() {
        log("first, tagged", "10.0.0.5")
        log("[AirPlay2Client] 10.0.0.5 info: model=X", null)
        log("Added Den to the cast", null)
        log("about someone else", null)
        log("last, tagged", "10.0.0.5")
        val lines = PacketLogger.logsFor("10.0.0.5", "Den")
        assertEquals(
            listOf("last, tagged", "Added Den to the cast", "[AirPlay2Client] 10.0.0.5 info: model=X", "first, tagged"),
            lines.map { it.message },
        )
    }

    @Test
    fun `only the most recently active speakers are remembered`() {
        repeat(40) { log("hello", "10.1.0.$it") }
        assertTrue(PacketLogger.logsFor("10.1.0.0", "").isEmpty())
        assertEquals(1, PacketLogger.logsFor("10.1.0.39", "").size)
    }

    @Test
    fun `clear forgets every speaker`() {
        log("hello", "10.0.0.1")
        PacketLogger.clear()
        assertTrue(PacketLogger.logsFor("10.0.0.1", "").isEmpty())
    }
}
