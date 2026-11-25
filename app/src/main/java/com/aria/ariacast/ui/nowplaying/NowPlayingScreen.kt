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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import com.aria.ariacast.pipeline.SpeakerSound
import com.aria.ariacast.sendspin.SendspinHub
import com.aria.ariacast.ui.ListBlock
import com.aria.ariacast.ui.ListRow
import com.aria.ariacast.ui.ListGroup
import com.aria.ariacast.ui.SwitchListRow
import com.aria.ariacast.ui.theme.StatTextStyle
import com.aria.ariacast.CastState
import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.palette.graphics.Palette
import com.aria.ariacast.ui.home.Artwork
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.sample
import kotlin.math.sqrt

const val KEY_LYRICS = "lyrics_enabled"

/**
 * Full-screen Now Playing, opened from the mini-player. The background takes a soft tint from
 * the artwork; below the transport sit volume, the speakers in the cast, sync and lyrics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(controller: AppController, onClose: () -> Unit) {
    val service by controller.service.collectAsState()
    val s = service
    val metadata by (s?.metadata ?: remember { MutableStateFlow<TrackMetadata?>(null) }).collectAsState()
    val artwork by (s?.artwork ?: remember { MutableStateFlow<ByteArray?>(null) }).collectAsState()
    val volume by (s?.volumePercent ?: remember { MutableStateFlow(50) }).collectAsState()
    val canSeek by (s?.canSeek ?: remember { MutableStateFlow(false) }).collectAsState()

    val bitmap = remember(artwork) { artwork?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() } }
    val swatch = remember(bitmap) { bitmap?.let { Palette.from(it).generate().let { p -> p.vibrantSwatch ?: p.dominantSwatch } } }
    val surface = MaterialTheme.colorScheme.surface
    val tint by animateColorAsState(swatch?.let { Color(it.rgb).copy(alpha = 0.35f).compositeOver(surface) } ?: surface, tween(600), label = "tint")

    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier.background(Brush.verticalGradient(0f to tint, 0.7f to surface)),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Artwork(artwork, Modifier.fillMaxWidth().aspectRatio(1f).shadow(16.dp, RoundedCornerShape(28.dp)), corner = 28.dp)
                val m = metadata
                Column {
                    Text(m?.title ?: stringResource(R.string.nothing_playing), style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sub = listOfNotNull(m?.artist, m?.album).joinToString(" · ")
                    if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                val livePosition = rememberLivePosition(m)
                // While dragging, and briefly after release until the player reports the new
                // position, show the chosen spot instead of snapping back.
                var seekTarget by remember { mutableStateOf<Long?>(null) }
                var dragging by remember { mutableStateOf(false) }
                LaunchedEffect(seekTarget, dragging) { if (seekTarget != null && !dragging) { delay(1500); seekTarget = null } }
                m?.durationMs?.takeIf { it > 0 }?.let { d ->
                    val position = (seekTarget ?: livePosition).coerceIn(0, d)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (canSeek && s != null) {
                            Slider(
                                value = position.toFloat() / d,
                                onValueChange = { dragging = true; seekTarget = (it * d).toLong() },
                                onValueChangeFinished = { dragging = false; seekTarget?.let { s.seekTo(it) } },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(progress = { (position.toFloat() / d).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                        }
                        Row {
                            Text(formatTime(position), style = StatTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            Text("-" + formatTime((d - position).coerceAtLeast(0)), style = StatTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(onClick = { s?.sendMediaCommand(MediaCommand.PREVIOUS) }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Filled.SkipPrevious, stringResource(R.string.previous), Modifier.size(36.dp))
                    }
                    FilledIconButton(onClick = { s?.sendMediaCommand(MediaCommand.TOGGLE) }, modifier = Modifier.size(80.dp)) {
                        Icon(if (m?.isPlaying == true) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(R.string.play_pause), Modifier.size(40.dp))
                    }
                    IconButton(onClick = { s?.sendMediaCommand(MediaCommand.NEXT) }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Filled.SkipNext, stringResource(R.string.next), Modifier.size(36.dp))
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.AutoMirrored.Filled.VolumeDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    var dragging by remember { mutableStateOf<Float?>(null) }
                    Slider(value = dragging ?: volume.toFloat(), valueRange = 0f..100f, onValueChange = { dragging = it },
                        onValueChangeFinished = { dragging?.let { s?.setVolume(it.toInt()) }; dragging = null }, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                s?.let { Visualizer(it, Modifier.fillMaxWidth().height(40.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) }
                s?.let { SpeakersInCast(controller, it) }
                s?.let { MultiroomSync(it) }
                s?.let { SpeakerSounds(it) }
                s?.let { SleepTimer(it) }
                LyricsSection(m, livePosition)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

/** Per-speaker delay so rooms line up; only while two or more speakers play. */
@Composable
private fun MultiroomSync(service: AudioCastService) {
    val destinations by service.activeDestinations.collectAsState()
    val state by service.state.collectAsState()
    if (state != CastState.CASTING || destinations.size < 2) return
    ListGroup(title = stringResource(R.string.multiroom_sync)) {
        ListBlock { Text(stringResource(R.string.multiroom_sync_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        destinations.forEach { dest ->
            ListBlock {
                var value by remember(dest.host) { mutableFloatStateOf(dest.delayMs.toFloat()) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(dest.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${value.toInt()} ms", style = StatTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Slider(value = value, onValueChange = { value = it }, valueRange = 0f..2000f, steps = 99,
                    onValueChangeFinished = { service.setDelay(dest.host, value.toInt()) })
            }
        }
    }
}

/** Bass, treble, level and leveling for each speaker, saved per speaker. */
@Composable
private fun SpeakerSounds(service: AudioCastService) {
    val destinations by service.activeDestinations.collectAsState()
    val sounds by service.speakerSounds.collectAsState()
    val state by service.state.collectAsState()
    // A Sendspin server pulls the stream itself, so there is nothing of ours to shape.
    val speakers = destinations.filter { it.platform != SendspinHub.PLATFORM }
    if (state != CastState.CASTING || speakers.isEmpty()) return
    ListGroup(title = stringResource(R.string.speaker_sound)) {
        speakers.forEach { dest ->
            val sound = sounds[dest.host] ?: service.soundFor(dest.host)
            var open by remember(dest.host) { mutableStateOf(false) }
            val flat = stringResource(R.string.speaker_sound_flat)
            ListRow(
                headline = dest.name,
                supporting = if (sound.isFlat) flat else soundSummary(sound),
                trailing = { Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null) },
                onClick = { open = !open },
            )
            if (open) {
                ListBlock {
                    DbSlider(stringResource(R.string.speaker_sound_bass), sound.bassDb) { service.setSpeakerSound(dest.host, sound.copy(bassDb = it)) }
                    DbSlider(stringResource(R.string.speaker_sound_treble), sound.trebleDb) { service.setSpeakerSound(dest.host, sound.copy(trebleDb = it)) }
                    DbSlider(stringResource(R.string.speaker_sound_level), sound.trimDb) { service.setSpeakerSound(dest.host, sound.copy(trimDb = it)) }
                }
                SwitchListRow(stringResource(R.string.speaker_sound_leveling), stringResource(R.string.speaker_sound_leveling_desc), sound.leveling,
                    { service.setSpeakerSound(dest.host, sound.copy(leveling = it)) })
                if (!sound.isFlat) {
                    ListRow(headline = stringResource(R.string.speaker_sound_reset), onClick = { service.setSpeakerSound(dest.host, SpeakerSound()) })
                }
            }
        }
    }
}

@Composable
private fun soundSummary(s: SpeakerSound): String = buildList {
    if (s.bassDb != 0) add(stringResource(R.string.speaker_sound_bass) + " %+d".format(s.bassDb))
    if (s.trebleDb != 0) add(stringResource(R.string.speaker_sound_treble) + " %+d".format(s.trebleDb))
    if (s.trimDb != 0) add(stringResource(R.string.speaker_sound_level) + " %+d".format(s.trimDb))
    if (s.leveling) add(stringResource(R.string.speaker_sound_leveling))
}.joinToString(" · ")

/** A -12..+12 dB slider that applies when released. */
@Composable
private fun DbSlider(label: String, value: Int, onCommit: (Int) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text("%+d dB".format(v.toInt()), style = StatTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(value = v, onValueChange = { v = it }, valueRange = -12f..12f, steps = 23, onValueChangeFinished = { onCommit(v.toInt()) })
}

private val SLEEP_TIMER_CHOICES = listOf(15, 30, 45, 60, 90)

/** Stops the cast after a while, fading the sound out over the last 30 seconds. */
@Composable
private fun SleepTimer(service: AudioCastService) {
    val state by service.state.collectAsState()
    val left by service.sleepTimerRemainingMs.collectAsState()
    if (state != CastState.CASTING) return
    ListGroup(title = stringResource(R.string.sleep_timer)) {
        ListBlock {
            Text(
                left?.let { stringResource(R.string.sleep_timer_left, formatTime(it + 999)) } ?: stringResource(R.string.sleep_timer_desc),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = left == null, onClick = { service.cancelSleepTimer() }, label = { Text(stringResource(R.string.sleep_timer_off)) })
                SLEEP_TIMER_CHOICES.forEach { minutes ->
                    FilterChip(selected = false, onClick = { service.setSleepTimer(minutes) },
                        label = { Text(stringResource(R.string.sleep_timer_minutes, minutes)) })
                }
            }
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
private fun LyricsSection(m: TrackMetadata?, position: Long) {
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
    ListGroup(title = stringResource(R.string.lyrics)) {
        SwitchListRow(stringResource(R.string.lyrics_off), if (enabled) null else stringResource(R.string.lyrics_privacy), enabled, {
            enabled = it; prefs.edit().putBoolean(KEY_LYRICS, it).apply()
        }, Icons.Filled.Lyrics)
        if (enabled) {
            ListBlock {
                val l = lyrics
                when {
                    l == null -> Text(stringResource(R.string.lyrics_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    l.synced.isNotEmpty() -> SyncedLyrics(l, position)
                    else -> Text(l.plain ?: "", style = MaterialTheme.typography.bodyLarge)
                }
            }
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
