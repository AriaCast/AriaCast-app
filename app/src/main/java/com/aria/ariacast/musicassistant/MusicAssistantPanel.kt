package com.aria.ariacast.musicassistant

import androidx.compose.foundation.layout.padding

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aria.ariacast.AudioCastService
import com.aria.ariacast.CastDestination
import com.aria.ariacast.CastState
import com.aria.ariacast.R
import com.aria.ariacast.ui.AppController
import com.aria.ariacast.ui.LeadingIcon
import com.aria.ariacast.ui.ListBlock
import com.aria.ariacast.ui.ListGroup
import com.aria.ariacast.ui.ListRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.LinearProgressIndicator
import com.aria.ariacast.ui.StatusDot
import com.aria.ariacast.ui.theme.StatusColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private const val KEY_TOKEN = "ma_token"

/**
 * Built-in Music Assistant control (was the example_MA plugin). Shown while casting to Music
 * Assistant's AriaCast Receiver: pick which Music Assistant player plays this phone's audio.
 */
@Composable
fun MusicAssistantPanel(controller: AppController) {
    val context = LocalContext.current
    // The token is a secret: keep it in the prefs file excluded from backups.
    val prefs = remember { context.getSharedPreferences(AudioCastService.SECURE_PREFS_NAME, Context.MODE_PRIVATE) }
    val service by controller.service.collectAsState()
    val state by (service?.state ?: remember { MutableStateFlow(CastState.OFF) }).collectAsState()
    val destinations by (service?.activeDestinations ?: remember { MutableStateFlow(emptyList<CastDestination>()) }).collectAsState()
    val ma = destinations.firstOrNull { it.platform == "AriaCast" && MaClient.isMusicAssistant(it.name) }
    if (ma == null || (state != CastState.CASTING && state != CastState.CONNECTING)) return

    var token by remember { mutableStateOf(prefs.getString(KEY_TOKEN, null)) }
    var players by remember { mutableStateOf<List<MaPlayer>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var needsAuth by remember { mutableStateOf(false) }
    var showLogin by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(ma.host, token, refresh) {
        while (true) {
            try {
                players = MaClient(ma.host, token).players()
                error = null; needsAuth = false
            } catch (e: MaAuthException) {
                needsAuth = true
            } catch (e: Exception) {
                error = e.message
            }
            delay(10_000)
        }
    }

    ListGroup(
        Modifier.padding(top = 8.dp),
        title = stringResource(R.string.ma_title),
        actions = { TextButton(onClick = { showLogin = true }) { Text(stringResource(if (needsAuth) R.string.ma_sign_in else R.string.ma_account)) } },
    ) {
        when {
            needsAuth -> ListBlock { Text(stringResource(R.string.ma_needs_auth), style = MaterialTheme.typography.bodyMedium) }
            error != null && players == null -> ListBlock {
                Text(stringResource(R.string.ma_unreachable, error ?: ""), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            players == null -> ListBlock { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            else -> {
                ListBlock { Text(stringResource(R.string.ma_pick_player), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                players!!.filter { it.available }.forEach { p ->
                    ListRow(
                        headline = p.name,
                        supporting = if (p.playingAriaCast) stringResource(R.string.status_playing) else null,
                        leading = { LeadingIcon(Icons.Filled.Speaker, active = p.playingAriaCast) },
                        selected = p.playingAriaCast,
                        onClick = {
                            scope.launch {
                                runCatching { MaClient(ma.host, token).playAriaCastOn(p.id) }.onFailure { error = it.message }
                                delay(1000); refresh++
                            }
                        },
                    )
                }
            }
        }
    }

    if (showLogin) MaLoginDialog(ma.host, token,
        onToken = { t -> token = t; prefs.edit().putString(KEY_TOKEN, t).apply(); showLogin = false; refresh++ },
        onDismiss = { showLogin = false })
}

@Composable
private fun MaLoginDialog(host: String, current: String?, onToken: (String?) -> Unit, onDismiss: () -> Unit) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var tokenText by remember { mutableStateOf(current ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ma_sign_in)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.username)) }, singleLine = true)
                OutlinedTextField(pass, { pass = it }, label = { Text(stringResource(R.string.password)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
                Text(stringResource(R.string.ma_or_token), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(tokenText, { tokenText = it }, label = { Text(stringResource(R.string.ma_token)) }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (user.isNotBlank()) scope.launch {
                    runCatching { MaClient.login(host, user, pass) }
                        .onSuccess(onToken).onFailure { error = it.message ?: "Login failed" }
                } else onToken(tokenText.trim().ifEmpty { null })
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
