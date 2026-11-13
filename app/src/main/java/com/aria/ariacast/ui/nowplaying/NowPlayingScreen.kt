package com.aria.ariacast.ui.nowplaying

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aria.ariacast.AudioCastService
import com.aria.ariacast.MediaCommand
import com.aria.ariacast.R
import com.aria.ariacast.TrackMetadata
import com.aria.ariacast.lyrics.LrcLib
import com.aria.ariacast.lyrics.Lyrics
import com.aria.ariacast.ui.AppController
import com.aria.ariacast.ui.Screen
import com.aria.ariacast.ui.SectionCard
import com.aria.ariacast.ui.home.Artwork
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.sample
import kotlin.math.sqrt

const val KEY_LYRICS = "lyrics_enabled"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(controller: AppController) {
    val service by controller.service.collectAsState()
    val s = service
    val metadata by (s?.metadata ?: remember { MutableStateFlow<TrackMetadata?>(null) }).collectAsState()
    val artwork by (s?.artwork ?: remember { MutableStateFlow<ByteArray?>(null) }).collectAsState()
    val volume by (s?.volumePercent ?: remember { MutableStateFlow(50) }).collectAsState()

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.now_playing)) },
            navigationIcon = { IconButton(onClick = { controller.navigate(Screen.HOME) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Artwork(artwork, Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f))
            val m = metadata
            Text(m?.title ?: stringResource(R.string.nothing_playing), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(m?.artist, m?.album).joinToString(" · "), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)

            val position = rememberLivePosition(m)
            m?.durationMs?.takeIf { it > 0 }?.let { d ->
                LinearProgressIndicator(progress = { (position.toFloat() / d).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconButton(onClick = { s?.sendMediaCommand(MediaCommand.PREVIOUS) }) { Icon(Icons.Filled.SkipPrevious, stringResource(R.string.previous), Modifier.size(36.dp)) }
                FilledIconButton(onClick = { s?.sendMediaCommand(MediaCommand.TOGGLE) }, modifier = Modifier.size(72.dp)) {
                    Icon(if (m?.isPlaying == true) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(R.string.play_pause), Modifier.size(40.dp))
                }
                IconButton(onClick = { s?.sendMediaCommand(MediaCommand.NEXT) }) { Icon(Icons.Filled.SkipNext, stringResource(R.string.next), Modifier.size(36.dp)) }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, null)
                var dragging by remember { mutableStateOf<Float?>(null) }
                Slider(value = dragging ?: volume.toFloat(), valueRange = 0f..100f, onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { s?.setVolume(it.toInt()) }; dragging = null }, modifier = Modifier.padding(start = 8.dp))
            }

            s?.let { Visualizer(it, Modifier.fillMaxWidth().height(96.dp)) }
            LyricsCard(m, position)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Track position, extrapolated between metadata updates while playing. */
@Composable
fun rememberLivePosition(m: TrackMetadata?): Long {
    val base = remember(m) { m?.positionMs ?: 0L }
    val since = remember(m) { System.currentTimeMillis() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(m?.isPlaying) {
        while (m?.isPlaying == true) { now = System.currentTimeMillis(); delay(250) }
    }
    return if (m?.isPlaying == true) base + (now - since).coerceAtLeast(0) else base
}

/** Bar visualizer over the audio being cast (was the visualizer plugin). */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun Visualizer(service: AudioCastService, modifier: Modifier = Modifier, bars: Int = 32, color: Color = MaterialTheme.colorScheme.primary) {
    var levels by remember { mutableStateOf(FloatArray(bars)) }
    LaunchedEffect(service) {
        service.audioBufferFlow.sample(33).collect { frame -> levels = barLevels(frame, bars, levels) }
    }
    Canvas(modifier) {
        val w = size.width / bars
        levels.forEachIndexed { i, l ->
            val h = (size.height * l).coerceAtLeast(4f)
            drawRoundRect(color, Offset(i * w + w * 0.15f, size.height - h), Size(w * 0.7f, h), CornerRadius(w * 0.35f))
        }
    }
}

/** RMS of [bars] consecutive slices of 16-bit stereo PCM, smoothed against [previous]. */
fun barLevels(pcm: ByteArray, bars: Int, previous: FloatArray): FloatArray {
    val samples = pcm.size / 2
    val per = (samples / bars).coerceAtLeast(1)
    return FloatArray(bars) { b ->
        var sum = 0.0
        val start = b * per
        for (i in start until minOf(start + per, samples)) {
            val v = ((pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)).toShort() / 32768.0
            sum += v * v
        }
        val rms = sqrt(sum / per).toFloat()
        val target = (rms * 3f).coerceIn(0f, 1f)
        val prev = previous.getOrElse(b) { 0f }
        if (target > prev) target else prev * 0.85f + target * 0.15f
    }
}

@Composable
private fun LyricsCard(m: TrackMetadata?, position: Long) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE) }
    var enabled by remember { mutableStateOf(prefs.getBoolean(KEY_LYRICS, false)) }
    var lyrics by remember { mutableStateOf<Lyrics?>(null) }
    val title = m?.title
    val artist = m?.artist
    LaunchedEffect(enabled, title, artist) {
        lyrics = null
        if (enabled && !title.isNullOrBlank() && !artist.isNullOrBlank()) lyrics = LrcLib.fetch(title, artist, m.album, m.durationMs)
    }
    SectionCard(title = stringResource(R.string.lyrics)) {
        FilterChip(selected = enabled, onClick = { enabled = !enabled; prefs.edit().putBoolean(KEY_LYRICS, enabled).apply() },
            label = { Text(stringResource(if (enabled) R.string.lyrics_on else R.string.lyrics_off)) })
        if (!enabled) Text(stringResource(R.string.lyrics_privacy), style = MaterialTheme.typography.bodySmall)
        val l = lyrics
        when {
            !enabled -> {}
            l == null -> Text(stringResource(R.string.lyrics_none), style = MaterialTheme.typography.bodyMedium)
            l.synced.isNotEmpty() -> SyncedLyrics(l, position)
            else -> Text(l.plain ?: "", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun SyncedLyrics(l: Lyrics, position: Long, color: Color = MaterialTheme.colorScheme.onSurface) {
    val current = l.lineAt(position)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in (current - 1).coerceAtLeast(0)..(current + 3).coerceAtMost(l.synced.lastIndex)) {
            val active = i == current
            val alpha by animateFloatAsState(if (active) 1f else 0.45f, label = "lyric")
            Text(l.synced[i].text.ifEmpty { "♪" }, color = color.copy(alpha = alpha),
                style = if (active) MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.titleMedium)
        }
    }
}
