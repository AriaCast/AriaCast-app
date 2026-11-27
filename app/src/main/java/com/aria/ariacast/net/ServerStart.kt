package com.aria.ariacast.net

import android.util.Log
import io.ktor.server.engine.ApplicationEngine

/**
 * Starts a local HTTP server, or returns null if it cannot listen.
 *
 * Ktor throws from start() when the port is taken, on the calling thread, which is often the main
 * one: an uncaught bind error closed the app. The port is taken more often than it seems: the
 * test build and the release build of AriaCast installed side by side both want the same ports.
 * So a server that cannot start is logged and stopped, and the feature it serves stays off.
 */
fun ApplicationEngine.startOrNull(tag: String, what: String): ApplicationEngine? = try {
    start(wait = false)
} catch (e: Exception) {
    // A bind failure arrives as a cancellation whose cause is the BindException.
    val cause = generateSequence<Throwable>(e) { it.cause }.last()
    Log.w(tag, "$what could not start: ${cause.javaClass.simpleName}: ${cause.message}")
    runCatching { stop(0, 0) }
    null
}
