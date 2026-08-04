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
}
