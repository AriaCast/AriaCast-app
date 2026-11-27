package com.aria.ariacast.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import com.aria.ariacast.AudioCastService
import com.aria.ariacast.PacketLogger
import com.aria.ariacast.R
import com.aria.ariacast.quirks.DeviceIdentity
import com.aria.ariacast.quirks.DeviceQuirks
import com.aria.ariacast.quirks.SpeakerReport

/**
 * "Report speaker": opens the share sheet with who the speaker is, what is known about it and
 * the log lines that concern it. The sheet's own "Copy" puts it on the clipboard.
 *
 * Works for any speaker in the list, cast to or not, so a speaker that never connects can be
 * reported too (its report then says it has no log yet).
 */
fun shareSpeakerReport(
    context: Context,
    service: AudioCastService?,
    platform: String?,
    name: String,
    host: String,
    extra: String?,
) {
    val identity = DeviceIdentity.of(platform, name, extra)
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    val active = service?.activeDestinations?.value?.firstOrNull { it.host == host }
    val sound = service?.soundFor(host)
    val report = SpeakerReport.build(
        context = SpeakerReport.Context(
            app = "${context.getString(R.string.app_name)} ${version ?: "?"}",
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            castState = service?.let { s -> listOfNotNull(s.state.value.name, s.lastError.value?.let { "last error: $it" }).joinToString(", ") },
            settings = if (service == null || sound == null) null else
                "delay=${active?.delayMs ?: 0} ms, bass=${sound.bassDb} dB, treble=${sound.trebleDb} dB, level=${sound.trimDb} dB, leveling=${sound.leveling}",
        ),
        identity = identity,
        host = host,
        known = DeviceQuirks.matching(identity),
        logs = PacketLogger.logsFor(host, name),
    )
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.report_speaker_subject, name))
        putExtra(Intent.EXTRA_TEXT, report)
    }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.report_speaker)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
