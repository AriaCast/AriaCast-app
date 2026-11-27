package com.aria.ariacast.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aria.ariacast.AudioCastService
import com.aria.ariacast.R
import com.aria.ariacast.airplay.AirPlayAuthRequiredException
import com.aria.ariacast.ui.doctor.DoctorScreen
import com.aria.ariacast.ui.home.MiniPlayer
import com.aria.ariacast.ui.home.SendScreen
import com.aria.ariacast.ui.nowplaying.NowPlayingScreen
import com.aria.ariacast.ui.onboarding.OnboardingScreen
import com.aria.ariacast.ui.receive.ReceiveScreen
import com.aria.ariacast.ui.receive.ReceiveTab
import com.aria.ariacast.ui.settings.KnownSpeakersScreen
import com.aria.ariacast.ui.settings.SettingsScreen

private data class Destination(val screen: Screen, val label: Int, val icon: ImageVector, val selectedIcon: ImageVector)

private val destinations = listOf(
    Destination(Screen.HOME, R.string.nav_send, Icons.Outlined.Cast, Icons.Filled.Cast),
    Destination(Screen.RECEIVE, R.string.nav_receive, Icons.Outlined.Speaker, Icons.Filled.Speaker),
    Destination(Screen.SETTINGS, R.string.settings, Icons.Outlined.Settings, Icons.Filled.Settings),
)

@Composable
fun AriaApp(controller: AppController) {
    val screen by controller.screen.collectAsState()
    val service by controller.service.collectAsState()

    // The destination a pushed screen returns to.
    var lastDestination by rememberSaveable {
        mutableStateOf(if (screen.isDestination) screen else if (controller.isTv) Screen.RECEIVE else Screen.HOME)
    }
    LaunchedEffect(screen) { if (screen.isDestination) lastDestination = screen }
    val back: () -> Unit = { controller.navigate(lastDestination) }

    if (screen == Screen.ONBOARDING) {
        OnboardingScreen(controller)
    } else {
        BackHandler(enabled = !screen.isDestination) { back() }
        BackHandler(enabled = screen.isDestination && screen != Screen.HOME) { controller.navigate(Screen.HOME) }

        Box(Modifier.fillMaxSize()) {
            MainShell(controller, if (screen.isDestination) screen else lastDestination)

            // Pushed screens slide over the shell, which stays composed (and keeps its scroll).
            var shown by remember { mutableStateOf<Screen?>(null) }
            if (!screen.isDestination) shown = screen
            AnimatedVisibility(
                visible = !screen.isDestination,
                enter = if (shown == Screen.NOW_PLAYING) slideInVertically(tween(320)) { it } + fadeIn(tween(160))
                else slideInHorizontally(tween(280)) { it / 8 } + fadeIn(tween(200)),
                exit = if (shown == Screen.NOW_PLAYING) slideOutVertically(tween(260)) { it } + fadeOut(tween(200, delayMillis = 60))
                else slideOutHorizontally(tween(220)) { it / 8 } + fadeOut(tween(160)),
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    when (shown) {
                        Screen.NOW_PLAYING -> NowPlayingScreen(controller, onClose = back)
                        Screen.RECEIVER_DISPLAY -> ReceiveScreen(controller, onBack = back)
                        Screen.DOCTOR -> DoctorScreen(controller, onBack = back)
                        Screen.KNOWN_SPEAKERS -> KnownSpeakersScreen(onBack = back)
                        else -> {}
                    }
                }
            }
        }
    }

    service?.let { PairingPrompt(it) }
}

/** Navigation bar on phones, rail on tablets and TVs; the mini-player sits above the content's bottom edge. */
@Composable
private fun MainShell(controller: AppController, current: Screen) {
    val useRail = controller.isTv || LocalConfiguration.current.screenWidthDp >= 600
    val service by controller.service.collectAsState()
    val castState by (service?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(com.aria.ariacast.CastState.OFF) }).collectAsState()
    val playerShown = castState == com.aria.ariacast.CastState.CASTING || castState == com.aria.ariacast.CastState.CONNECTING
    val content = @Composable { modifier: Modifier ->
        // The mini-player floats over the destination; lists get matching room at their end.
        Box(modifier) {
            CompositionLocalProvider(LocalFloatingInset provides if (playerShown) 96.dp else 0.dp) {
                AnimatedContent(
                    targetState = current,
                    transitionSpec = {
                        (fadeIn(tween(210, delayMillis = 90)) + scaleIn(tween(210, delayMillis = 90), initialScale = 0.97f)) togetherWith fadeOut(tween(90))
                    },
                    label = "destination",
                    modifier = Modifier.fillMaxSize(),
                ) { d ->
                    when (d) {
                        Screen.RECEIVE -> ReceiveTab(controller)
                        Screen.SETTINGS -> SettingsScreen(controller, onBack = null)
                        else -> SendScreen(controller)
                    }
                }
            }
            MiniPlayer(controller, Modifier.align(Alignment.BottomCenter).widthIn(max = ContentMaxWidth).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 16.dp))
        }
    }
    if (useRail) {
        Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            NavigationRail(Modifier.fillMaxHeight()) {
                Spacer(Modifier.weight(1f))
                destinations.forEach { d ->
                    NavigationRailItem(
                        selected = current == d.screen,
                        onClick = { controller.navigate(d.screen) },
                        icon = { Icon(if (current == d.screen) d.selectedIcon else d.icon, null) },
                        label = { Text(stringResource(d.label)) },
                    )
                }
                Spacer(Modifier.weight(1f))
            }
            content(Modifier.weight(1f).navigationBarsPadding())
        }
    } else {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            content(Modifier.weight(1f))
            NavigationBar {
                destinations.forEach { d ->
                    NavigationBarItem(
                        selected = current == d.screen,
                        onClick = { controller.navigate(d.screen) },
                        icon = { Icon(if (current == d.screen) d.selectedIcon else d.icon, null) },
                        label = { Text(stringResource(d.label)) },
                    )
                }
            }
        }
    }
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
            Column {
                Text(stringResource(if (isPassword) R.string.airplay_password_message else R.string.airplay_pin_message, label))
                Spacer(Modifier.padding(top = 12.dp))
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
