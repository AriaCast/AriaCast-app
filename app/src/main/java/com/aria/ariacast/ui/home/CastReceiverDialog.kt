package com.aria.ariacast.ui.home

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aria.ariacast.AudioCastService
import com.aria.ariacast.R

/** Sets the application ID of the custom Google Cast receiver (cast-receiver/README.md). */
@Composable
fun CastReceiverDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE) }
    var id by remember { mutableStateOf(prefs.getString(AudioCastService.KEY_CAST_RECEIVER_APP_ID, "") ?: "") }
    val valid = id.isEmpty() || id.matches(Regex("[0-9A-Fa-f]{8}"))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cast_receiver_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.cast_receiver_desc), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(id, { id = it.trim().take(8) }, label = { Text(stringResource(R.string.cast_receiver_app_id)) },
                    singleLine = true, isError = !valid, placeholder = { Text("A1B2C3D4") })
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                prefs.edit().putString(AudioCastService.KEY_CAST_RECEIVER_APP_ID, id.uppercase()).apply(); onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
