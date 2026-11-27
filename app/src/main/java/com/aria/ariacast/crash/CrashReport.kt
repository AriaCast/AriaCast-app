package com.aria.ariacast.crash

import java.io.PrintWriter
import java.io.StringWriter

/** What a crash report says. Pure, so it can be tested; [CrashReporter] does the catching. */
object CrashReport {
    /** A very deep trace (a stack overflow) would not fit through an Intent; the top of it is what matters. */
    const val MAX_CHARS = 30_000

    fun format(error: Throwable, threadName: String, app: String, device: String, time: String): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            appendLine("AriaCast crash report")
            appendLine("Time: $time")
            appendLine("App: $app")
            appendLine("Device: $device")
            appendLine("Thread: $threadName")
            appendLine()
            append(trace)
        }
        return if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS) + "\n... (cut)"
    }
}
