package com.aria.ariacast.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aria.ariacast.AudioCastService
import com.aria.ariacast.R
import com.aria.ariacast.quirks.KnownSpeaker
import com.aria.ariacast.quirks.KnownSpeakersStore
import com.aria.ariacast.quirks.SpeakerListSync
import com.aria.ariacast.quirks.Verdict
import com.aria.ariacast.ui.LeadingIcon
import com.aria.ariacast.ui.ListBlock
import com.aria.ariacast.ui.ListGroup
import com.aria.ariacast.ui.ListRow
import com.aria.ariacast.ui.ScreenList
import com.aria.ariacast.ui.SubScreenScaffold
import com.aria.ariacast.ui.SwitchListRow
import com.aria.ariacast.ui.theme.StatusColors
import kotlinx.coroutines.launch

/**
 * The public list of speakers we know something about: what works, what works with a catch,
 * what does not, and what AriaCast cannot reach. The same list as docs/SPEAKERS.md, kept up to
 * date from there (see [SpeakerListSync]).
 */
@Composable
fun KnownSpeakersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(KnownSpeakersStore.current) }
    var auto by remember { mutableStateOf(SpeakerListSync.isEnabled(prefs)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var lastOk by remember { mutableStateOf(prefs.getLong(SpeakerListSync.KEY_LAST_OK, 0)) }

    SubScreenScaffold(title = stringResource(R.string.known_speakers), onBack = onBack) { padding ->
        ScreenList(Modifier.padding(padding)) {
            item {
                Text(
                    stringResource(R.string.known_speakers_intro), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                ListGroup {
                    SwitchListRow(stringResource(R.string.known_speakers_auto), stringResource(R.string.known_speakers_auto_desc), auto, {
                        auto = it; prefs.edit().putBoolean(SpeakerListSync.KEY_AUTO, it).apply()
                    })
                    val updated = if (lastOk > 0) DateUtils.getRelativeTimeSpanString(lastOk, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                    else stringResource(R.string.known_speakers_never)
                    ListRow(
                        stringResource(R.string.known_speakers_version, list.version, list.speakers.size),
                        supporting = status ?: stringResource(R.string.known_speakers_checked, updated),
                        leading = { LeadingIcon(Icons.Filled.Sync) },
                        trailing = {
                            TextButton(enabled = !busy, onClick = {
                                busy = true; status = null
                                scope.launch {
                                    val outcome = SpeakerListSync.sync(context)
                                    list = KnownSpeakersStore.current
                                    lastOk = prefs.getLong(SpeakerListSync.KEY_LAST_OK, 0)
                                    status = when (outcome) {
                                        is SpeakerListSync.Outcome.Updated -> context.getString(R.string.known_speakers_now, outcome.version)
                                        is SpeakerListSync.Outcome.UpToDate -> context.getString(R.string.known_speakers_current)
                                        is SpeakerListSync.Outcome.Failed -> context.getString(R.string.known_speakers_failed, outcome.why)
                                    }
                                    busy = false
                                }
                            }) { Text(stringResource(R.string.known_speakers_update)) }
                        },
                    )
                }
            }
            Verdict.entries.forEach { verdict ->
                val rows = list.speakers.filter { it.verdict == verdict }
                if (rows.isNotEmpty()) item(key = verdict.id) {
                    ListGroup(title = stringResource(verdictLabel(verdict))) { rows.forEach { SpeakerEntryRow(it) } }
                }
            }
            item {
                ListGroup(Modifier.padding(top = 8.dp)) {
                    ListRow(stringResource(R.string.known_speakers_report), supporting = stringResource(R.string.known_speakers_report_desc),
                        leading = { LeadingIcon(Icons.Filled.BugReport) }) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SPEAKER_ISSUE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
    }
}

private const val SPEAKER_ISSUE_URL = "https://github.com/AriaCast/AriaCast-app/issues/new?template=speaker-report.yml"

private fun verdictLabel(v: Verdict) = when (v) {
    Verdict.GOOD -> R.string.verdict_good
    Verdict.PARTIAL -> R.string.verdict_partial
    Verdict.BAD -> R.string.verdict_bad
    Verdict.UNSUPPORTED -> R.string.verdict_unsupported
}

private fun verdictIcon(v: Verdict): Pair<ImageVector, Color?> = when (v) {
    Verdict.GOOD -> Icons.Filled.CheckCircle to StatusColors.Connected
    Verdict.PARTIAL -> Icons.Filled.Warning to StatusColors.Warning
    Verdict.BAD -> Icons.Filled.Error to null
    Verdict.UNSUPPORTED -> Icons.Filled.Block to StatusColors.Available
}

/** `[#48](https://...)` shows as `#48`: the text is public but not trusted, so no links are made from it. */
private fun plain(text: String) = text.replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")

@Composable
private fun SpeakerEntryRow(k: KnownSpeaker) {
    var open by remember(k.id) { mutableStateOf(false) }
    val (icon, tint) = verdictIcon(k.verdict)
    ListRow(
        headline = k.speaker,
        supporting = k.protocol,
        leading = { Icon(icon, null, tint = tint ?: MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) },
        trailing = { Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null) },
        onClick = { open = !open },
    )
    if (open) {
        ListBlock {
            if (k.symptoms.isNotEmpty()) Detail(stringResource(R.string.known_speakers_symptoms), k.symptoms.joinToString("\n") { "• ${plain(it)}" })
            k.whatToDo?.let { Detail(stringResource(R.string.known_speakers_what_to_do), plain(it)) }
            k.since?.let { Detail(stringResource(R.string.known_speakers_since), it) }
            Detail(stringResource(R.string.known_speakers_evidence), plain(k.evidence))
        }
    }
}

@Composable
private fun Detail(label: String, text: String) {
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
