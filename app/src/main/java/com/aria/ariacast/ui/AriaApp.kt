package com.aria.ariacast.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.aria.ariacast.AudioCastService
import com.aria.ariacast.R
import com.aria.ariacast.airplay.AirPlayAuthRequiredException
import com.aria.ariacast.ui.doctor.DoctorScreen
import com.aria.ariacast.ui.home.HomeScreen
import com.aria.ariacast.ui.nowplaying.NowPlayingScreen
import com.aria.ariacast.ui.onboarding.OnboardingScreen
import com.aria.ariacast.ui.receive.ReceiveScreen

@Composable
fun AriaApp(controller: AppController) {
    val screen by controller.screen.collectAsState()
    val service by controller.service.collectAsState()

    BackHandler(enabled = screen != Screen.HOME && screen != Screen.ONBOARDING) { controller.navigate(Screen.HOME) }

    AnimatedContent(targetState = screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { s ->
        when (s) {
            Screen.HOME -> HomeScreen(controller)
            Screen.NOW_PLAYING -> NowPlayingScreen(controller)
            Screen.RECEIVE -> ReceiveScreen(controller)
            Screen.DOCTOR -> DoctorScreen(controller)
            Screen.ONBOARDING -> OnboardingScreen(controller)
        }
    }

    service?.let { PairingPrompt(it) }
}

/** AirPlay PIN / password prompt raised by the service while connecting. */
@Composable
private fun PairingPrompt(service: AudioCastService) {
    val request by service.pairingPinRequest.collectAsState()
    val r = request ?: return
    val isPassword = r.kind == AirPlayAuthRequiredException.Kind.PASSWORD
    var value by remember(r) { mutableStateOf("") }
    val label = r.name.ifEmpty { r.host }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(if (isPassword) R.string.airplay_password_title else R.string.airplay_pin_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(stringResource(if (isPassword) R.string.airplay_password_message else R.string.airplay_pin_message, label))
                OutlinedTextField(
                    value = value,
                    onValueChange = { v -> value = if (isPassword) v else v.filter(Char::isDigit).take(8) },
                    singleLine = true,
                    placeholder = { if (!isPassword) Text("0000") },
                    visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.NumberPassword),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotEmpty()) service.submitPairingPin(r.host, value) else service.resetPairingPinRequest() }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = { service.resetPairingPinRequest() }) { Text(stringResource(R.string.cancel)) } },
    )
}
