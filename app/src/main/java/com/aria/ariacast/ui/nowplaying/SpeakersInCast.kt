package com.aria.ariacast.ui.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aria.ariacast.AudioCastService
import com.aria.ariacast.CastDestination
import com.aria.ariacast.R
import com.aria.ariacast.Server
import com.aria.ariacast.ui.AppController
import com.aria.ariacast.ui.SectionCard
import com.aria.ariacast.ui.home.platformIcon
import com.aria.ariacast.ui.home.platformLabel

private fun Server.toDestination() = CastDestination(name, host, port, platform, extra = extra)

/** Speakers in the running cast: remove one, add one, or move the music to another room. */
@Composable
fun SpeakersInCast(controller: AppController, service: AudioCastService) {
    val active by service.activeDestinations.collectAsState()
    val servers by controller.servers.collectAsState()
    var picker by remember { mutableStateOf<Boolean?>(null) } // true = move, false = add
    if (active.isEmpty()) return
    SectionCard(title = stringResource(R.string.cast_speakers)) {
        active.forEach { d ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(platformIcon(d.platform), null)
                Text("${d.name} · ${platformLabel(d.platform)}", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                if (active.size > 1) IconButton(onClick = { service.removeDestination(d) }) { Icon(Icons.Filled.Close, stringResource(R.string.remove)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picker = false }) { Icon(Icons.Filled.Add, null); Text(stringResource(R.string.cast_add_speaker)) }
            OutlinedButton(onClick = { picker = true }) { Text(stringResource(R.string.cast_move)) }
        }
    }
    picker?.let { move ->
        val candidates = servers.filter { s -> active.none { it.host == s.host && it.platform == s.platform } }
        AlertDialog(
            onDismissRequest = { picker = null },
            title = { Text(stringResource(if (move) R.string.cast_move else R.string.cast_add_speaker)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (candidates.isEmpty()) Text(stringResource(R.string.cast_no_other_speakers))
                    candidates.forEach { s ->
                        TextButton(onClick = {
                            val before = active.toList()
                            service.addDestination(s.toDestination())
                            if (move) before.forEach(service::removeDestination)
                            picker = null
                        }, modifier = Modifier.fillMaxWidth()) { Text("${s.name} (${platformLabel(s.platform)})", Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { picker = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
