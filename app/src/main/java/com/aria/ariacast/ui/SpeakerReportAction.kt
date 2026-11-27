package com.aria.ariacast.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.aria.ariacast.AudioCastService
import com.aria.ariacast.PacketLogger
import com.aria.ariacast.R
import com.aria.ariacast.quirks.DeviceIdentity
import com.aria.ariacast.quirks.DeviceQuirks
import com.aria.ariacast.quirks.SpeakerIssue
import com.aria.ariacast.quirks.SpeakerReport

/**
 * What the report needs about the phone, the app and the cast in progress. Works for any speaker
 * in the list, cast to or not, so a speaker that never connects can be reported too (its report
 * then says it has no log yet).
 */
private fun reportContext(context: Context, service: AudioCastService?, host: String): SpeakerReport.Context {
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    val active = service?.activeDestinations?.value?.firstOrNull { it.host == host }
    val sound = service?.soundFor(host)
    return SpeakerReport.Context(
        app = "${context.getString(R.string.app_name)} ${version ?: "?"}",
        android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        device = "${Build.MANUFACTURER} ${Build.MODEL}",
        castState = service?.let { s -> listOfNotNull(s.state.value.name, s.lastError.value?.let { "last error: $it" }).joinToString(", ") },
        settings = if (service == null || sound == null) null else
            "delay=${active?.delayMs ?: 0} ms, bass=${sound.bassDb} dB, treble=${sound.trebleDb} dB, level=${sound.trimDb} dB, leveling=${sound.leveling}",
    )
}

private fun fullReport(context: Context, service: AudioCastService?, platform: String?, name: String, host: String, extra: String?): String {
    val identity = DeviceIdentity.of(platform, name, extra)
    return SpeakerReport.build(reportContext(context, service, host), identity, host, DeviceQuirks.matching(identity), PacketLogger.logsFor(host, name))
}

/** Opens the share sheet with the full report; its own "Copy" puts it on the clipboard. */
fun shareSpeakerLog(context: Context, service: AudioCastService?, platform: String?, name: String, host: String, extra: String?) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.report_speaker_subject, name))
        putExtra(Intent.EXTRA_TEXT, fullReport(context, service, platform, name, host, extra))
    }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.report_speaker)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * "Report speaker": opens a pre-filled issue on GitHub and puts the log on the clipboard, since a
 * log is far too long for an address. [verdict] pre-selects how it works when the user has said so
 * already (see [SpeakerIssue]).
 */
fun reportSpeaker(
    context: Context,
    service: AudioCastService?,
    platform: String?,
    name: String,
    host: String,
    extra: String?,
    verdict: String? = null,
) {
    val identity = DeviceIdentity.of(platform, name, extra)
    val ctx = reportContext(context, service, host)
    val log = SpeakerReport.build(ctx, identity, host, DeviceQuirks.matching(identity), PacketLogger.logsFor(host, name))
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AriaCast speaker report", log))
    val url = SpeakerIssue.url(identity, SpeakerReport.summary(ctx, identity, DeviceQuirks.matching(identity)), ctx.app.substringAfter(' '), verdict)
    Toast.makeText(context, context.getString(R.string.report_speaker_log_copied), Toast.LENGTH_LONG).show()
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
