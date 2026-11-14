package com.aria.ariacast.ui.receive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import com.aria.ariacast.AudioCastService
import com.aria.ariacast.MediaCommand
import com.aria.ariacast.R
import com.aria.ariacast.TrackMetadata
import com.aria.ariacast.lyrics.LrcLib
import com.aria.ariacast.lyrics.Lyrics
import com.aria.ariacast.receiver.ReceiverHub
import com.aria.ariacast.receiver.ReceiverMetadata
import com.aria.ariacast.receiver.ReceiverService
import com.aria.ariacast.ui.AppController
import com.aria.ariacast.ui.qrBitmap
import com.aria.ariacast.ui.nowplaying.KEY_LYRICS
import com.aria.ariacast.ui.nowplaying.SyncedLyrics
import com.aria.ariacast.ui.nowplaying.rememberLivePosition
import kotlinx.coroutines.delay
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date
import kotlin.random.Random

/**
 * Full-screen receiver: what tablets, Echo Show-type devices and TVs show while they act
 * as a speaker. Colors come from the artwork; while idle it shows the device name, a clock
 * and a "Scan to cast here" QR code. Content drifts a little every minute against burn-in.
 */
@Composable
fun ReceiveScreen(controller: AppController, onBack: () -> Unit) {
    val context = LocalContext.current
    val hub = remember { ReceiverHub.get(context) }
    val running by hub.running.collectAsState()
    val sender by hub.sender.collectAsState()
    val meta by hub.metadata.collectAsState()
    val artworkBytes by hub.artwork.collectAsState()

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val bitmap = remember(artworkBytes) { artworkBytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() } }
    val palette = remember(bitmap) { bitmap?.let { Palette.from(it).generate() } }
    val c1 by animateColorAsState(Color(palette?.getDarkVibrantColor(0xFF2A0F1C.toInt()) ?: 0xFF2A0F1C.toInt()), tween(1200), label = "c1")
    val c2 by animateColorAsState(Color(palette?.getDarkMutedColor(0xFF0D0A12.toInt()) ?: 0xFF0D0A12.toInt()), tween(1200), label = "c2")
    val onBg = if (c1.luminance() > 0.5f) Color.Black else Color.White

    // Burn-in protection: drift by up to 12 dp once a minute.
    var drift by remember { mutableStateOf(0 to 0) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); drift = Random.nextInt(-12, 13) to Random.nextInt(-12, 13) } }

    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c1, c2)))) {
        if (!controller.isTv) {
            IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(8.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = onBg)
            }
        }
        Box(Modifier.fillMaxSize().offset(drift.first.dp, drift.second.dp).padding(32.dp), contentAlignment = Alignment.Center) {
            val dlna = remember { com.aria.ariacast.dlna.DlnaRenderer.get(context) }
            val video by dlna.isVideo.collectAsState()
            when {
                video && dlna.player != null -> androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx -> androidx.media3.ui.PlayerView(ctx).apply { player = dlna.player; useController = true } },
                    modifier = Modifier.fillMaxSize(),
                )
                !running -> NotRunning(hub, onBg)
                sender == null -> Idle(hub, onBg)
                else -> Playing(hub, meta, bitmap, onBg)
            }
        }
    }
}

@Composable
private fun NotRunning(hub: ReceiverHub, onBg: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(hub.name, color = onBg, style = MaterialTheme.typography.displaySmall)
        Text(stringResource(R.string.receive_off), color = onBg.copy(alpha = 0.7f))
        Button(onClick = { hub.start() }) { Text(stringResource(R.string.receive_start)) }
    }
}

@Composable
private fun Idle(hub: ReceiverHub, onBg: Color) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(1000) } }
    val ip = remember { ReceiverService.localIpv4() }
    val link = remember(ip, hub.name) { ip?.let { "ariacast://$it:${ReceiverService.PORT}?type=ariacast&name=" + URLEncoder.encode(hub.name, "UTF-8") } }
    val qr = remember(link) { link?.let { qrBitmap(it, 512) } }
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp

    val text = @Composable {
        Column(horizontalAlignment = if (landscape) Alignment.Start else Alignment.CenterHorizontally) {
            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(now), color = onBg, fontSize = 96.sp, fontWeight = FontWeight.Light)
            Text(hub.name, color = onBg, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.receive_idle_hint), color = onBg.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyLarge,
                textAlign = if (landscape) TextAlign.Start else TextAlign.Center)
        }
    }
    val code = @Composable {
        qr?.let {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(it.asImageBitmap(), stringResource(R.string.receive_scan), Modifier.size(180.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(8.dp))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.receive_scan), color = onBg.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    if (landscape) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(48.dp)) { text(); code() }
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(32.dp)) { text(); code() }
    }
}

@Composable
private fun Playing(hub: ReceiverHub, meta: ReceiverMetadata, bitmap: Bitmap?, onBg: Color) {
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    val asTrack = TrackMetadata(meta.title, meta.artist, meta.album, meta.artworkUrl, meta.durationMs, meta.positionMs, meta.isPlaying)
    val position = rememberLivePosition(asTrack)
    val context = LocalContext.current
    val lyricsOn = remember { context.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_LYRICS, false) }
    var lyrics by remember { mutableStateOf<Lyrics?>(null) }
    LaunchedEffect(lyricsOn, meta.title, meta.artist) {
        lyrics = if (lyricsOn && !meta.title.isNullOrBlank() && !meta.artist.isNullOrBlank())
            LrcLib.fetch(meta.title, meta.artist, meta.album, meta.durationMs.takeIf { it > 0 }) else null
    }

    val art = @Composable { mod: Modifier ->
        Box(mod.aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(onBg.copy(alpha = 0.1f))) {
            bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
    }
    val info = @Composable {
        Column(horizontalAlignment = if (landscape) Alignment.Start else Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(meta.title ?: "", color = onBg, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(meta.artist, meta.album).joinToString(" · "), color = onBg.copy(alpha = 0.75f), style = MaterialTheme.typography.titleLarge, maxLines = 2)
            lyrics?.takeIf { it.synced.isNotEmpty() }?.let { SyncedLyrics(it, position, onBg) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                val colors = IconButtonDefaults.iconButtonColors(contentColor = onBg)
                IconButton(onClick = { hub.sendCommand(MediaCommand.PREVIOUS) }, colors = colors) { Icon(Icons.Filled.SkipPrevious, stringResource(R.string.previous), Modifier.size(40.dp)) }
                IconButton(onClick = { hub.sendCommand(MediaCommand.TOGGLE) }, colors = colors, modifier = Modifier.size(72.dp)) {
                    Icon(if (meta.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(R.string.play_pause), Modifier.size(56.dp))
                }
                IconButton(onClick = { hub.sendCommand(MediaCommand.NEXT) }, colors = colors) { Icon(Icons.Filled.SkipNext, stringResource(R.string.next), Modifier.size(40.dp)) }
            }
        }
    }
    if (landscape) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(48.dp), modifier = Modifier.fillMaxWidth()) {
            art(Modifier.fillMaxHeight(0.8f))
            Box(Modifier.weight(1f)) { info() }
        }
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(32.dp)) {
            art(Modifier.fillMaxWidth(0.8f))
            info()
        }
    }
}


