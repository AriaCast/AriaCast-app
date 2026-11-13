package com.aria.ariacast.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aria.ariacast.R
import com.aria.ariacast.receiver.ReceiverHub
import com.aria.ariacast.ui.AppController

/**
 * First-run wizard: what AriaCast does, the permissions it needs and why, and which role
 * this device plays (send, receive or both). Speakers are already being discovered meanwhile.
 */
@Composable
fun OnboardingScreen(controller: AppController) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    val access by controller.notificationAccess.collectAsState()
    val servers by controller.servers.collectAsState()
    var permsAsked by remember { mutableStateOf(false) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permsAsked = true; step++ }
    val steps = 4

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LinearProgressIndicator(progress = { (step + 1f) / steps }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.weight(1f))
        AnimatedContent(step, label = "step") { s ->
            Column(Modifier.widthIn(max = 520.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (s) {
                    0 -> StepHeader(Icons.Filled.Cast, stringResource(R.string.onb_welcome_title), stringResource(R.string.onb_welcome_body))
                    1 -> {
                        StepHeader(Icons.Filled.NotificationsActive, stringResource(R.string.onb_permissions_title), stringResource(R.string.onb_permissions_body))
                        if (access) Text(stringResource(R.string.onb_notifications_done), color = MaterialTheme.colorScheme.primary)
                        else OutlinedButton(onClick = { controller.requestNotificationAccess() }) { Text(stringResource(R.string.onb_allow_notifications)) }
                    }
                    2 -> {
                        StepHeader(Icons.Filled.SwapHoriz, stringResource(R.string.onb_role_title), stringResource(R.string.onb_role_body))
                        val hub = remember { ReceiverHub.get(context) }
                        RoleCard(Icons.Filled.Cast, stringResource(R.string.onb_role_send), stringResource(R.string.onb_role_send_desc)) { hub.stop(); step++ }
                        RoleCard(Icons.Filled.Speaker, stringResource(R.string.onb_role_receive), stringResource(R.string.onb_role_receive_desc)) { hub.start(); hub.startAtBoot = controller.isTv; step++ }
                        RoleCard(Icons.Filled.SwapHoriz, stringResource(R.string.onb_role_both), stringResource(R.string.onb_role_both_desc)) { hub.start(); step++ }
                    }
                    else -> StepHeader(Icons.Filled.CheckCircle, stringResource(R.string.onb_done_title),
                        if (servers.isEmpty()) stringResource(R.string.onb_done_body_none) else stringResource(R.string.onb_done_body, servers.size))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (step > 0) TextButton(onClick = { step-- }) { Text(stringResource(R.string.back)) } else Spacer(Modifier)
            when (step) {
                1 -> Button(onClick = {
                    val needed = buildList {
                        add(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                    }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                    if (needed.isEmpty() || permsAsked) step++ else perms.launch(needed.toTypedArray())
                }) { Text(stringResource(R.string.next)) }
                2 -> Spacer(Modifier)
                steps - 1 -> Button(onClick = { controller.finishOnboarding() }) { Text(stringResource(R.string.onb_start)) }
                else -> Button(onClick = { step++ }) { Text(stringResource(R.string.next)) }
            }
        }
    }
}

@Composable
private fun StepHeader(icon: ImageVector, title: String, body: String) {
    Icon(icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
    Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    Text(body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RoleCard(icon: ImageVector, title: String, desc: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null)
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
