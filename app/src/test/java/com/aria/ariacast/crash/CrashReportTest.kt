package com.aria.ariacast.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportTest {
    private fun report(error: Throwable, thread: String = "main") =
        CrashReport.format(error, thread, "com.aria.ariacast.test 2.0.0-test", "Google Pixel 8, Android 14 (API 34)", "2026-11-27 11:40:00")

    @Test fun itSaysWhatCrashedAndWhere() {
        val text = report(IllegalStateException("no service"), thread = "DefaultDispatcher-worker-3")
        assertTrue(text.startsWith("AriaCast crash report"))
        assertTrue(text.contains("Thread: DefaultDispatcher-worker-3"))
        assertTrue(text.contains("App: com.aria.ariacast.test 2.0.0-test"))
        assertTrue(text.contains("Device: Google Pixel 8, Android 14 (API 34)"))
        assertTrue(text.contains("java.lang.IllegalStateException: no service"))
        assertTrue(text.contains("at com.aria.ariacast.crash.CrashReportTest"))
    }

    @Test fun theCauseIsKept() {
        val text = report(RuntimeException("outer", IllegalArgumentException("the real reason")))
        assertTrue(text.contains("Caused by: java.lang.IllegalArgumentException: the real reason"))
    }

    @Test fun anErrorWithoutAMessageStillReports() {
        assertTrue(report(NullPointerException()).contains("java.lang.NullPointerException"))
    }

    @Test fun aHugeTraceIsCutButKeepsItsTop() {
        fun deep(n: Int): Nothing = if (n == 0) throw StackOverflowError("deep") else deep(n - 1)
        val error = try { deep(5000) } catch (e: StackOverflowError) { e }
        val text = report(error)
        assertEquals(CrashReport.MAX_CHARS + "\n... (cut)".length, text.length)
        assertTrue(text.contains("java.lang.StackOverflowError: deep"))
    }
}
