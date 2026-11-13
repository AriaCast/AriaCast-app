package com.aria.ariacast.video

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.aria.ariacast.DiscoveryManager
import com.aria.ariacast.R
import com.aria.ariacast.Server
import com.aria.ariacast.ui.SectionCard
import com.aria.ariacast.ui.home.platformIcon
import com.aria.ariacast.ui.home.platformLabel
import com.aria.ariacast.ui.theme.AriaTheme

/**
 * Video casting: pick (or share to AriaCast) a video file or link and play it on a TV over
 * Google Cast, DLNA or AirPlay. The TV fetches the file itself; the phone only serves it.
 */
class VideoCastActivity : ComponentActivity() {
    private lateinit var discovery: DiscoveryManager
    private val shared = mutableStateOf<Pair<Uri, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        discovery = DiscoveryManager(this)
        readIntent(intent)
        setContent { AriaTheme { VideoScreen(discovery, shared.value, onBack = ::finish) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    override fun onStart() { super.onStart(); discovery.startDiscovery() }
    override fun onStop() { super.onStop(); discovery.stopDiscovery() }

    @Suppress("DEPRECATION")
    private fun readIntent(i: Intent?) {
        val uri: Uri? = when (i?.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)
                ?: i.getStringExtra(Intent.EXTRA_TEXT)?.let { t -> Regex("""https?://\S+""").find(t)?.value?.let(Uri::parse) }
            else -> null
        }
        if (uri != null) shared.value = uri to (i?.type?.takeIf { it.startsWith("video") || it.startsWith("audio") } ?: guessMime(uri))
    }

    private fun guessMime(uri: Uri): String = contentResolver.getType(uri) ?: when (uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
        "m3u8" -> "application/x-mpegURL"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        else -> "video/mp4"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoScreen(discovery: DiscoveryManager, initial: Pair<Uri, String>?, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val servers by discovery.servers.collectAsState()
    val session by VideoSession.state.collectAsState()
    var video by remember(initial) { mutableStateOf(initial) }
    var subtitles by remember { mutableStateOf<String?>(null) }
    var link by remember { mutableStateOf("") }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Keep read access after this screen closes: the TV keeps fetching the file.
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            video = uri to (context.contentResolver.getType(uri) ?: "video/mp4")
        }
    }
    val pickSubs = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        subtitles = uri?.let { u -> context.contentResolver.openInputStream(u)?.use { it.readBytes().decodeToString() } }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.video_cast_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (val s = session) {
                is VideoSession.State.Playing -> SectionCard(title = s.title) {
                    Text(stringResource(R.string.video_playing_on, s.target), style = MaterialTheme.typography.bodyMedium)
                    var drag by remember { mutableStateOf<Float?>(null) }
                    if (s.duration > 0) {
                        Slider(value = drag ?: s.position.toFloat(), valueRange = 0f..s.duration.toFloat(),
                            onValueChange = { drag = it }, onValueChangeFinished = { drag?.let { VideoSession.seek(it.toDouble()) }; drag = null })
                        Text("${fmt(s.position)} / ${fmt(s.duration)}", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledIconButton(onClick = { VideoSession.togglePause() }) {
                            Icon(if (s.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, stringResource(R.string.play_pause))
                        }
                        IconButton(onClick = { VideoSession.stop(context) }) { Icon(Icons.Filled.Stop, stringResource(R.string.stop)) }
                    }
                }
                is VideoSession.State.Loading -> SectionCard(title = null) { Text(stringResource(R.string.video_loading, s.target)) }
                is VideoSession.State.Failed -> SectionCard(title = null) { Text(stringResource(R.string.video_failed, s.message), color = MaterialTheme.colorScheme.error) }
                VideoSession.State.Idle -> {}
            }

            SectionCard(title = stringResource(R.string.video_what)) {
                Text(video?.first?.let { displayName(context, it) } ?: stringResource(R.string.video_none), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pickVideo.launch(arrayOf("video/*")) }) { Text(stringResource(R.string.video_choose)) }
                    OutlinedButton(onClick = { pickSubs.launch(arrayOf("*/*")) }) {
                        Text(stringResource(if (subtitles == null) R.string.video_add_subtitles else R.string.video_subtitles_added))
                    }
                }
                OutlinedTextField(link, { link = it.trim() }, label = { Text(stringResource(R.string.video_link)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(stringResource(R.string.video_link_hint)) },
                    trailingIcon = { if (link.startsWith("http")) androidx.compose.material3.TextButton(onClick = {
                        video = Uri.parse(link) to if (link.contains(".m3u8")) "application/x-mpegURL" else "video/mp4"
                    }) { Text("OK") } })
            }

            Text(stringResource(R.string.video_where), style = MaterialTheme.typography.titleMedium)
            val targets = servers.filter(VideoTarget::supports)
            if (targets.isEmpty()) Text(stringResource(R.string.video_no_targets), style = MaterialTheme.typography.bodyMedium)
            targets.forEach { server ->
                TargetRow(server, enabled = video != null) {
                    val (uri, mime) = video ?: return@TargetRow
                    VideoSession.start(context, server, uri, mime, displayName(context, uri), subtitles)
                }
            }
        }
    }
}

@Composable
private fun TargetRow(server: Server, enabled: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(platformIcon(server.platform), null)
            Column {
                Text(server.name, style = MaterialTheme.typography.titleMedium)
                Text(platformLabel(server.platform), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun displayName(context: android.content.Context, uri: Uri): String =
    if (uri.scheme == "content") context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: "Video" else uri.lastPathSegment ?: uri.toString()

private fun fmt(seconds: Double): String {
    val t = seconds.toLong()
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, (t / 60) % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
}
