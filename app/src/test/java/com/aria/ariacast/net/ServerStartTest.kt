package com.aria.ariacast.net

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.net.ServerSocket
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerStartTest {
    @Test fun ktorItselfThrowsOnATakenPort() {
        // What closed the app: start() throws on the calling thread, often the main one.
        ServerSocket(0).use { taken ->
            val thrown = runCatching { embeddedServer(CIO, port = taken.localPort, host = "0.0.0.0") {}.start(wait = false) }.exceptionOrNull()
            assertTrue("expected a bind error, got $thrown", generateSequence(thrown) { it.cause }.any { it is java.net.BindException })
        }
    }

    @Test fun aTakenPortGivesNullInsteadOfThrowing() {
        // Another app (the other build of AriaCast, say) already listens on the port.
        ServerSocket(0).use { taken ->
            assertNull(embeddedServer(CIO, port = taken.localPort, host = "0.0.0.0") {}.startOrNull("test", "server"))
        }
    }

    @Test fun aFreePortStarts() {
        val port = ServerSocket(0).use { it.localPort }
        val engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {}.startOrNull("test", "server")
        assertNotNull(engine)
        engine!!.stop(0, 0)
    }
}
