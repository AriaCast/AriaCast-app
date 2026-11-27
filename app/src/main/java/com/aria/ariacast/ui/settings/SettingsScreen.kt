package com.aria.ariacast.ui.settings

import android.app.Activity
import android.app.LocaleManager
import android.os.LocaleList
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.aria.ariacast.AirPlayPinsActivity
import com.aria.ariacast.AudioCastService
import com.aria.ariacast.NfcWriteActivity
import com.aria.ariacast.PacketLogActivity
import com.aria.ariacast.PluginsActivity
import com.aria.ariacast.ProtocolsActivity
import com.aria.ariacast.R
import com.aria.ariacast.SettingsActivity
import com.aria.ariacast.ThemeUtils
import com.aria.ariacast.UpdateManager
import com.aria.ariacast.automation.CastShortcutActivity
import com.aria.ariacast.ui.AppController
import com.aria.ariacast.ui.CompanionActivity
import com.aria.ariacast.ui.DestinationInsets
import com.aria.ariacast.ui.LeadingIcon
import com.aria.ariacast.ui.ListGroup
import com.aria.ariacast.ui.ListRow
import com.aria.ariacast.ui.Screen
import com.aria.ariacast.ui.ScreenList
import com.aria.ariacast.ui.SubScreenScaffold
import com.aria.ariacast.ui.SwitchListRow
import com.aria.ariacast.ui.home.CastReceiverDialog
import com.aria.ariacast.ui.home.WebRemoteDialog
import com.aria.ariacast.video.VideoCastActivity
import kotlinx.coroutines.launch

private val languages = listOf(
    "" to null, "en" to "English", "de" to "Deutsch", "es" to "Español", "fr" to "Français", "it" to "Italiano",
    "nl" to "Nederlands", "pl" to "Polski", "ta" to "தமிழ்", "ja" to "日本語", "zh" to "简体中文",
)

private enum class SettingsDialog { THEME, LANGUAGE, NOTIFICATIONS, PLUGIN_FOLDER, WEB_REMOTE, CAST_RECEIVER, CONTRIBUTORS }

/**
 * Settings destination. Everything that used to live in the overflow menu is here, grouped by
 * what it is for: general, casting, features, extensions, diagnostics, about.
 */
@Composable
fun SettingsScreen(controller: AppController, onBack: (() -> Unit)?) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE) }
    val pluginPrefs = remember { context.getSharedPreferences("plugins_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val themeMode by ThemeUtils.mode.collectAsState()
    val multiroom by controller.multiroomEnabled.collectAsState()
    val notificationAccess by controller.notificationAccess.collectAsState()
    var video by remember { mutableStateOf(prefs.getBoolean(SettingsActivity.KEY_VIDEO_ENABLED, false)) }
    var automation by remember { mutableStateOf(prefs.getBoolean(CastShortcutActivity.KEY_AUTOMATION, false)) }
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            pluginPrefs.edit().putString("plugin_folder_uri", uri.toString()).apply()
            Toast.makeText(context, context.getString(R.string.plugin_folder_updated), Toast.LENGTH_SHORT).show()
        }
    }
    val open = { cls: Class<*> -> controller.openActivity(cls) }
    var currentLanguage by remember { mutableStateOf(appLanguage(context)) }

    SubScreenScaffold(
        title = stringResource(R.string.settings), onBack = onBack,
        contentWindowInsets = if (onBack == null) DestinationInsets else androidx.compose.material3.ScaffoldDefaults.contentWindowInsets,
    ) { padding ->
        ScreenList(Modifier.padding(padding)) {
            item {
                ListGroup(title = stringResource(R.string.general)) {
                    ListRow(stringResource(R.string.appearance), supporting = themeLabel(themeMode), leading = { LeadingIcon(Icons.Filled.DarkMode) }) { dialog = SettingsDialog.THEME }
                    ListRow(
                        stringResource(R.string.language),
                        supporting = languages.firstOrNull { it.first == currentLanguage }?.second ?: stringResource(R.string.language_default),
                        leading = { LeadingIcon(Icons.Filled.Language) },
                    ) { dialog = SettingsDialog.LANGUAGE }
                }
            }
            item {
                ListGroup(title = stringResource(R.string.casting)) {
                    ListRow(stringResource(R.string.protocols), supporting = stringResource(R.string.protocols_desc), leading = { LeadingIcon(Icons.Filled.Hub) }) { open(ProtocolsActivity::class.java) }
                    SwitchListRow(stringResource(R.string.multiroom), stringResource(R.string.multiroom_desc), multiroom, { controller.setMultiroomEnabled(it) }, Icons.Filled.SpeakerGroup)
                    SwitchListRow(stringResource(R.string.enable_video), stringResource(R.string.enable_video_desc), video, {
                        video = it; prefs.edit().putBoolean(SettingsActivity.KEY_VIDEO_ENABLED, it).apply()
                    }, Icons.Filled.Videocam)
                    ListRow(
                        stringResource(R.string.notification_access), supporting = stringResource(R.string.notification_access_desc),
                        leading = { LeadingIcon(Icons.Filled.NotificationsActive, active = notificationAccess) },
                    ) { dialog = SettingsDialog.NOTIFICATIONS }
                    ListRow(stringResource(R.string.manage_airplay_pins), supporting = stringResource(R.string.manage_airplay_pins_desc), leading = { LeadingIcon(Icons.Filled.Key) }) { open(AirPlayPinsActivity::class.java) }
                    ListRow(stringResource(R.string.cast_receiver_title), leading = { LeadingIcon(Icons.Filled.Tv) }) { dialog = SettingsDialog.CAST_RECEIVER }
                    ListRow(stringResource(R.string.reset_last_server), supporting = stringResource(R.string.reset_last_server_desc), leading = { LeadingIcon(Icons.Filled.RestartAlt) }) {
                        prefs.edit()
                            .remove(AudioCastService.KEY_LAST_SERVER_HOST)
                            .remove(AudioCastService.KEY_LAST_SERVER_PORT)
                            .remove(AudioCastService.KEY_LAST_SERVER_NAME)
                            .apply()
                        Toast.makeText(context, context.getString(R.string.server_cleared), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            item {
                ListGroup(title = stringResource(R.string.settings_features)) {
                    ListRow(stringResource(R.string.companion), supporting = stringResource(R.string.companion_desc), leading = { LeadingIcon(Icons.Filled.Usb) }) { open(CompanionActivity::class.java) }
                    ListRow(stringResource(R.string.video_cast_title), leading = { LeadingIcon(Icons.Filled.Movie) }) { open(VideoCastActivity::class.java) }
                    ListRow(stringResource(R.string.web_remote), supporting = stringResource(R.string.web_remote_desc), leading = { LeadingIcon(Icons.Filled.Public) }) { dialog = SettingsDialog.WEB_REMOTE }
                    ListRow(stringResource(R.string.write_nfc_tag), supporting = stringResource(R.string.write_nfc_tag_desc), leading = { LeadingIcon(Icons.Filled.Nfc) }) { open(NfcWriteActivity::class.java) }
                    SwitchListRow(stringResource(R.string.automation_allow), null, automation, {
                        automation = it; prefs.edit().putBoolean(CastShortcutActivity.KEY_AUTOMATION, it).apply()
                    }, Icons.Filled.SmartToy)
                }
            }
            item {
                ListGroup(title = stringResource(R.string.extensions)) {
                    ListRow(stringResource(R.string.plugins), supporting = stringResource(R.string.manage_plugins_desc), leading = { LeadingIcon(Icons.Filled.Extension) }) { open(PluginsActivity::class.java) }
                    ListRow(stringResource(R.string.plugin_folder), supporting = stringResource(R.string.change_plugin_folder_desc), leading = { LeadingIcon(Icons.Filled.Folder) }) { dialog = SettingsDialog.PLUGIN_FOLDER }
                }
            }
            item {
                ListGroup(title = stringResource(R.string.settings_diagnostics)) {
                    ListRow(stringResource(R.string.doctor_title), supporting = stringResource(R.string.doctor_desc), leading = { LeadingIcon(Icons.Filled.HealthAndSafety) }) { controller.navigate(Screen.DOCTOR) }
                    ListRow(stringResource(R.string.known_speakers), supporting = stringResource(R.string.known_speakers_desc), leading = { LeadingIcon(Icons.Filled.Speaker) }) { controller.navigate(Screen.KNOWN_SPEAKERS) }
                    ListRow(stringResource(R.string.packet_logs), supporting = stringResource(R.string.packet_logs_desc), leading = { LeadingIcon(Icons.AutoMirrored.Filled.ReceiptLong) }) { open(PacketLogActivity::class.java) }
                }
            }
            item {
                ListGroup(title = stringResource(R.string.about)) {
                    ListRow(stringResource(R.string.check_for_updates), leading = { LeadingIcon(Icons.Filled.SystemUpdate) }) {
                        scope.launch { UpdateManager(context).checkForUpdates(manual = true) }
                    }
                    ListRow(stringResource(R.string.github_repo), supporting = stringResource(R.string.github_repo_desc), leading = { LeadingIcon(Icons.Filled.Code) }) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SettingsActivity.GITHUB_URL)))
                    }
                    ListRow(stringResource(R.string.contributors), supporting = stringResource(R.string.contributors_desc), leading = { LeadingIcon(Icons.Filled.Favorite) }) { dialog = SettingsDialog.CONTRIBUTORS }
                    ListRow(stringResource(R.string.version_format, stringResource(R.string.app_version)), leading = { LeadingIcon(Icons.Filled.Info) })
                }
            }
        }
    }

    when (dialog) {
        SettingsDialog.THEME -> ChoiceDialog(
            title = stringResource(R.string.select_appearance),
            options = listOf(
                ThemeUtils.MODE_NIGHT_FOLLOW_SYSTEM to stringResource(R.string.theme_follow_system),
                ThemeUtils.MODE_NIGHT_NO to stringResource(R.string.theme_light),
                ThemeUtils.MODE_NIGHT_YES to stringResource(R.string.theme_dark),
            ),
            selected = themeMode,
            onSelect = { ThemeUtils.setMode(context, it); dialog = null },
            onDismiss = { dialog = null },
        )
        SettingsDialog.LANGUAGE -> ChoiceDialog(
            title = stringResource(R.string.select_language),
            options = languages.map { (code, label) -> code to (label ?: stringResource(R.string.language_default)) },
            selected = currentLanguage,
            onSelect = { code ->
                dialog = null
                currentLanguage = code
                setAppLanguage(context, code)
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.NOTIFICATIONS -> AlertDialog(
            onDismissRequest = { dialog = null },
            icon = { androidx.compose.material3.Icon(Icons.Filled.NotificationsActive, null) },
            title = { Text(stringResource(R.string.notification_access_required_title)) },
            text = { Text(stringResource(R.string.notification_access_required_message), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = { dialog = null; context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                    Text(stringResource(R.string.open_notification_settings))
                }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
        )
        SettingsDialog.PLUGIN_FOLDER -> AlertDialog(
            onDismissRequest = { dialog = null },
            icon = { androidx.compose.material3.Icon(Icons.Filled.Folder, null) },
            title = { Text(stringResource(R.string.plugin_folder)) },
            text = { Text(stringResource(R.string.change_plugin_folder_desc)) },
            confirmButton = { TextButton(onClick = { dialog = null; pickFolder.launch(null) }) { Text(stringResource(R.string.select_folder)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        dialog = null
                        pluginPrefs.edit().remove("plugin_folder_uri").apply()
                        Toast.makeText(context, context.getString(R.string.plugin_folder_updated), Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.use_default)) }
                    TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
        SettingsDialog.WEB_REMOTE -> WebRemoteDialog { dialog = null }
        SettingsDialog.CAST_RECEIVER -> CastReceiverDialog { dialog = null }
        SettingsDialog.CONTRIBUTORS -> AlertDialog(
            onDismissRequest = { dialog = null },
            icon = { androidx.compose.material3.Icon(Icons.Filled.Favorite, null) },
            title = { Text(stringResource(R.string.contributors_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.contributors_message))
                    CONTRIBUTORS.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
                                .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${c.login}"))) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(
                                painterResource(c.avatar), null,
                                Modifier.size(40.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop,
                            )
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(c.name, style = MaterialTheme.typography.bodyLarge)
                                Text("@${c.login}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dialog = null; context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SettingsActivity.CONTRIBUTORS_URL))) }) {
                    Text(stringResource(R.string.contributors_code))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { dialog = null; context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SettingsActivity.TRANSLATORS_URL))) }) {
                        Text(stringResource(R.string.contributors_translations))
                    }
                    TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.close)) }
                }
            },
        )
        null -> {}
    }
}

private class Contributor(val name: String, val login: String, @androidx.annotation.DrawableRes val avatar: Int)

/** Code contributors, most commits first. Avatars are bundled so the dialog works offline. */
private val CONTRIBUTORS = listOf(
    Contributor("Lorenzo Imbastari", "AirPlr", R.drawable.avatar_airplr),
    Contributor("Daniel Freiermuth", "daniel-freiermuth", R.drawable.avatar_daniel_freiermuth),
    Contributor("Peter Meiser", "meiser79", R.drawable.avatar_meiser79),
    Contributor("Highwall2016", "Highwall2016", R.drawable.avatar_highwall2016),
    Contributor("artbre", "arthurbre", R.drawable.avatar_arthurbre),
    Contributor("damian5602", "damian5602", R.drawable.avatar_damian5602),
    Contributor("TamilNeram", "TamilNeram", R.drawable.avatar_tamilneram),
)

/** The per-app language ("" = follow the system). Android 13+ keeps it in LocaleManager. */
private fun appLanguage(context: Context): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.getSystemService(LocaleManager::class.java).applicationLocales.get(0)?.language ?: ""
    } else {
        AppCompatDelegate.getApplicationLocales().get(0)?.language ?: ""
    }

private fun setAppLanguage(context: Context, code: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // The system stores it and recreates the activity.
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            if (code.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(code)
    } else {
        AppCompatDelegate.setApplicationLocales(if (code.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(code))
        (context as? Activity)?.recreate()
    }
}

@Composable
private fun themeLabel(mode: Int): String = when (mode) {
    ThemeUtils.MODE_NIGHT_NO -> stringResource(R.string.theme_light)
    ThemeUtils.MODE_NIGHT_YES -> stringResource(R.string.theme_dark)
    else -> stringResource(R.string.theme_follow_system)
}

/** Single-choice list dialog: tapping an option applies it and closes. */
@Composable
fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onSelect(value) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = null, modifier = Modifier.padding(12.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
