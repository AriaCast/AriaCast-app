package com.aria.ariacast.ui

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.aria.ariacast.AudioCastService
import com.aria.ariacast.R
import com.aria.ariacast.quirks.DeviceIdentity
import com.aria.ariacast.quirks.DeviceQuirks
import com.aria.ariacast.quirks.FeedbackLedger
import com.aria.ariacast.quirks.SpeakerFeedback
import com.aria.ariacast.quirks.Status
import com.aria.ariacast.ui.theme.AriaTheme

/**
 * "Does it work?", asked once about a speaker after a minute of casting to it. It is a plain dialog
 * over whatever is on screen (or a notification when the app is closed, which opens this). The
 * answer stays on the phone; only if the user chooses to report does anything open on GitHub.
 */
class SpeakerFeedbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra(EXTRA_NAME)
        val host = intent.getStringExtra(EXTRA_HOST)
        if (name == null || host == null) {
            finish()
            return
        }
        // Answering from the notification's buttons is answering: the notification goes.
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        val platform = intent.getStringExtra(EXTRA_PLATFORM)
        val extra = intent.getStringExtra(EXTRA_EXTRA)
        val start = intent.getStringExtra(EXTRA_ANSWER)
        val castState = intent.getStringExtra(EXTRA_CAST_STATE)
        setContent { AriaTheme { FeedbackFlow(platform, name, host, extra, start, castState, onFinish = ::finish) } }
    }

    companion object {
        const val EXTRA_PLATFORM = "com.aria.ariacast.FEEDBACK_PLATFORM"
        const val EXTRA_NAME = "com.aria.ariacast.FEEDBACK_NAME"
        const val EXTRA_HOST = "com.aria.ariacast.FEEDBACK_HOST"
        const val EXTRA_EXTRA = "com.aria.ariacast.FEEDBACK_EXTRA"

        /** The cast's state when it was asked, so a report made here still says what the cast was doing. */
        const val EXTRA_CAST_STATE = "com.aria.ariacast.FEEDBACK_CAST_STATE"

        /** Set by the notification's buttons: [ANSWER_WORKS] or [ANSWER_PROBLEM]. */
        const val EXTRA_ANSWER = "com.aria.ariacast.FEEDBACK_ANSWER"
        const val ANSWER_WORKS = "works"
        const val ANSWER_PROBLEM = "problem"

        /** The cast failed: the question is whether to report it, not whether it works. */
        const val ANSWER_FAILED = "failed"
        const val NOTIFICATION_ID = 7
    }
}

private enum class Step { NONE, ASK, FAILED, PROBLEM, SHARE }

@Composable
private fun FeedbackFlow(platform: String?, name: String, host: String, extra: String?, start: String?, castState: String?, onFinish: () -> Unit) {
    val context = LocalContext.current
    val ledger = remember { FeedbackLedger.of(context.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)) }
    val key = remember { SpeakerFeedback.key(platform, name) }
    val identity = remember { DeviceIdentity.of(platform, name, extra) }
    var step by remember { mutableStateOf(when (start) { null -> Step.ASK; "failed" -> Step.FAILED; else -> Step.NONE }) }
    // After a failed cast the user has already said it does not work; "it plays but" is not on offer.
    val failed = start == "failed"

    fun works(): Step {
        ledger.record(key, Status.SUPPORTED)
        if (SpeakerFeedback.offerSharingAfterWorks(DeviceQuirks.bestMatch(identity))) return Step.SHARE
        Toast.makeText(context, context.getString(R.string.feedback_thanks), Toast.LENGTH_SHORT).show()
        onFinish()
        return Step.NONE
    }

    // A button of the notification already is an answer: go straight to what follows it.
    LaunchedEffect(Unit) {
        when (start) {
            "works" -> step = works()
            "problem" -> step = Step.PROBLEM
        }
    }

    when (step) {
        Step.NONE -> Unit
        Step.ASK -> AlertDialog(
            onDismissRequest = onFinish,
            title = { Text(stringResource(R.string.feedback_title, name)) },
            text = { Text(stringResource(R.string.feedback_ask, name)) },
            confirmButton = { TextButton(onClick = { step = works() }) { Text(stringResource(R.string.feedback_works)) } },
            dismissButton = { TextButton(onClick = { step = Step.PROBLEM }) { Text(stringResource(R.string.feedback_problem)) } },
        )
        Step.FAILED -> AlertDialog(
            onDismissRequest = onFinish,
            title = { Text(stringResource(R.string.feedback_failed_title, name)) },
            text = { Text(stringResource(R.string.feedback_failed_text, name)) },
            confirmButton = { TextButton(onClick = { step = Step.PROBLEM }) { Text(stringResource(R.string.feedback_report)) } },
            dismissButton = { TextButton(onClick = onFinish) { Text(stringResource(R.string.feedback_no_thanks)) } },
        )
        Step.SHARE -> AlertDialog(
            onDismissRequest = onFinish,
            title = { Text(stringResource(R.string.feedback_share_title)) },
            text = { Text(stringResource(R.string.feedback_share_text, name)) },
            confirmButton = {
                TextButton(onClick = {
                    reportSpeaker(context, null, platform, name, host, extra, SpeakerFeedback.formLabel(Status.SUPPORTED), castState = castState)
                    onFinish()
                }) { Text(stringResource(R.string.feedback_share)) }
            },
            dismissButton = { TextButton(onClick = onFinish) { Text(stringResource(R.string.feedback_no_thanks)) } },
        )
        Step.PROBLEM -> ProblemDialog(
            initialBroken = failed,
            onCancel = onFinish,
            onContinue = { status, text ->
                ledger.record(key, status)
                reportSpeaker(context, null, platform, name, host, extra, SpeakerFeedback.formLabel(status), text, castState)
                onFinish()
            },
        )
    }
}

@Composable
private fun ProblemDialog(initialBroken: Boolean, onCancel: () -> Unit, onContinue: (Status, String) -> Unit) {
    var broken by remember { mutableStateOf(initialBroken) }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.feedback_problem_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.selectableGroup()) {
                    listOf(false to R.string.feedback_problem_partial, true to R.string.feedback_problem_broken).forEach { (isBroken, label) ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = broken == isBroken, onClick = { broken = isBroken }, role = Role.RadioButton).padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = broken == isBroken, onClick = null)
                            Text(stringResource(label), modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(SPEAKER_NOTE_MAX) }, minLines = 3,
                    label = { Text(stringResource(R.string.feedback_problem_hint)) }, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        // The list asks a speaker that does not simply work to say what happens, so the note is needed.
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onContinue(if (broken) Status.UNSUPPORTED else Status.QUIRKS, text) }) {
                Text(stringResource(R.string.feedback_continue))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.feedback_cancel)) } },
    )
}

private const val SPEAKER_NOTE_MAX = 600
