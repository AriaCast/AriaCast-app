package com.aria.ariacast.crash

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In a debug build, an uncaught crash opens a screen with the stack trace and a Share button,
 * so a tester without a computer can say why the app stopped. The screen runs in its own
 * process (see [CrashReportActivity]), which is what lets it stay up while this one dies.
 * Release builds are left alone.
 */
object CrashReporter {
    fun install(app: Application) {
        if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
                val report = CrashReport.format(
                    error, thread.name,
                    app = "${app.packageName} ${version ?: "?"}",
                    device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
                )
                app.startActivity(
                    Intent(app, CrashReportActivity::class.java)
                        .putExtra(CrashReportActivity.EXTRA_REPORT, report)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            } catch (_: Throwable) {
                // Reporting must never hide the crash itself.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The crash screen lives in a second process, which must not start the app a second time. */
    fun isCrashProcess(): Boolean = Application.getProcessName().endsWith(":crash")
}
