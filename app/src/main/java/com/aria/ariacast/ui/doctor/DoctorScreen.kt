package com.aria.ariacast.ui.doctor

import android.content.ClipData
import android.content.ClipboardManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aria.ariacast.CastState
import com.aria.ariacast.MediaNotificationListener
import com.aria.ariacast.PacketLogActivity
import com.aria.ariacast.R
import com.aria.ariacast.companion.CompanionHub
import com.aria.ariacast.companion.CompanionStatus
import com.aria.ariacast.pipeline.SourceKind
import com.aria.ariacast.ui.AppController
import com.aria.ariacast.ui.CompanionActivity
import com.aria.ariacast.ui.LeadingIcon
import com.aria.ariacast.ui.ListBlock
import com.aria.ariacast.ui.ListGroup
import com.aria.ariacast.ui.ListRow
import com.aria.ariacast.ui.ScreenList
import com.aria.ariacast.ui.SubScreenScaffold
import com.aria.ariacast.ui.theme.StatusColors
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.fillMaxWidth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

enum class CheckStatus { OK, WARN, FAIL, PENDING }

data class Check(val title: String, val status: CheckStatus, val detail: String, val fix: Pair<String, () -> Unit>? = null)

/**
 * Connection doctor (was the health_check plugin): walks the path from this device to the
 * speaker one step at a time and says which step is broken.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoctorScreen(controller: AppController, onBack: () -> Unit) {
    val context = LocalContext.current
    val service by controller.service.collectAsState()
    val servers by controller.servers.collectAsState()
    var checks by remember { mutableStateOf<List<Check>>(emptyList()) }
    var silentSince by remember { mutableStateOf(0L) }
    var lastSent by remember { mutableStateOf(-1L) }

    LaunchedEffect(service) {
        while (true) {
            val s = service
            val list = mutableListOf<Check>()
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val lan = caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            list += Check(context.getString(R.string.doctor_network), if (lan) CheckStatus.OK else CheckStatus.FAIL,
                context.getString(if (lan) R.string.doctor_network_ok else R.string.doctor_network_fail))

            val access = MediaNotificationListener.isEnabled(context)
            list += Check(context.getString(R.string.doctor_notifications), if (access) CheckStatus.OK else CheckStatus.WARN,
                context.getString(if (access) R.string.doctor_notifications_ok else R.string.doctor_notifications_warn),
                if (access) null else context.getString(R.string.allow) to { controller.requestNotificationAccess() })

            list += Check(context.getString(R.string.doctor_speakers), if (servers.isNotEmpty()) CheckStatus.OK else CheckStatus.WARN,
                if (servers.isNotEmpty()) context.getString(R.string.doctor_speakers_ok, servers.size) else context.getString(R.string.doctor_speakers_warn))

            val state = s?.state?.value ?: CastState.OFF
            if (s == null || state == CastState.OFF) {
                list += Check(context.getString(R.string.doctor_casting), CheckStatus.PENDING, context.getString(R.string.doctor_casting_idle))
            } else {
                list += Check(context.getString(R.string.doctor_casting), when (state) {
                    CastState.CASTING -> CheckStatus.OK; CastState.ERROR -> CheckStatus.FAIL; else -> CheckStatus.PENDING
                }, s.lastError.value ?: state.name)

                // Source: is there an actual signal?
                val silent = s.sourceSilent.value == true
                val playing = s.metadata.value?.isPlaying == true
                val now = System.currentTimeMillis()
                silentSince = if (silent && playing) (if (silentSince == 0L) now else silentSince) else 0L
                val blocked = silentSince != 0L && now - silentSince > 3000 && s.sourceKind.value == SourceKind.SYSTEM_CAPTURE
                list += when {
                    blocked -> Check(context.getString(R.string.doctor_source), CheckStatus.FAIL, context.getString(R.string.doctor_source_blocked),
                        context.getString(R.string.companion) to { controller.openActivity(CompanionActivity::class.java) })
                    silent -> Check(context.getString(R.string.doctor_source), CheckStatus.WARN, context.getString(R.string.doctor_source_silent))
                    else -> Check(context.getString(R.string.doctor_source), CheckStatus.OK, context.getString(R.string.doctor_source_ok))
                }

                // Receiver reachable?
                s.activeDestinations.value.filter { it.port > 0 }.forEach { d ->
                    val ok = withContext(Dispatchers.IO) {
                        runCatching { Socket().use { it.connect(InetSocketAddress(d.host, d.port), 2000) }; true }.getOrDefault(false)
                    }
                    list += Check(context.getString(R.string.doctor_reach, d.name), if (ok) CheckStatus.OK else CheckStatus.FAIL,
                        context.getString(if (ok) R.string.doctor_reach_ok else R.string.doctor_reach_fail, d.host, d.port))
                }

                // Data flowing?
                val stats = s.stats.value
                val flowing = stats.sentFrames > lastSent && lastSent >= 0
                lastSent = stats.sentFrames
                list += Check(context.getString(R.string.doctor_flow), when {
                    state != CastState.CASTING -> CheckStatus.PENDING
                    flowing || stats.receivedFrames > 0 -> CheckStatus.OK
                    else -> CheckStatus.WARN
                }, context.getString(R.string.doctor_flow_detail, stats.sentFrames, stats.receivedFrames, stats.droppedFrames))
            }

            when (val c = CompanionHub.get(context).status.value) {
                is CompanionStatus.Connected -> {
                    val st = CompanionHub.get(context).stats.value
                    list += Check(context.getString(R.string.companion), if ((st?.overruns ?: 0) == 0L) CheckStatus.OK else CheckStatus.WARN,
                        context.getString(R.string.doctor_companion, c.info.firmwareVersionString, st?.overruns ?: 0))
                }
                is CompanionStatus.Error -> list += Check(context.getString(R.string.companion), CheckStatus.FAIL, c.message)
                else -> {}
            }
            checks = list
            delay(2000)
        }
    }

    SubScreenScaffold(title = stringResource(R.string.doctor_title), onBack = onBack) { padding ->
        ScreenList(Modifier.padding(padding)) {
            item {
                Text(stringResource(R.string.doctor_desc), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            item {
                ListGroup {
                    if (checks.isEmpty()) ListBlock { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    checks.forEach { c ->
                        val (icon, tint) = when (c.status) {
                            CheckStatus.OK -> Icons.Filled.CheckCircle to StatusColors.Connected
                            CheckStatus.WARN -> Icons.Filled.Warning to StatusColors.Warning
                            CheckStatus.FAIL -> Icons.Filled.Error to MaterialTheme.colorScheme.error
                            CheckStatus.PENDING -> Icons.Filled.RadioButtonUnchecked to MaterialTheme.colorScheme.outline
                        }
                        ListRow(
                            headline = c.title,
                            supporting = c.detail,
                            leading = { Icon(icon, c.status.name, tint = tint, modifier = Modifier.padding(8.dp)) },
                            trailing = c.fix?.let { (label, action) -> { TextButton(onClick = action) { Text(label) } } },
                        )
                    }
                }
            }
            item {
                ListGroup(Modifier.padding(top = 8.dp)) {
                    ListRow(stringResource(R.string.doctor_copy), leading = { LeadingIcon(Icons.Filled.ContentCopy) }) {
                        val report = buildString {
                            appendLine("AriaCast connection report")
                            checks.forEach { appendLine("[${it.status}] ${it.title}: ${it.detail}") }
                            service?.let { appendLine(it.getStatsJson()) }
                        }
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AriaCast report", report))
                        Toast.makeText(context, context.getString(R.string.doctor_copied), Toast.LENGTH_SHORT).show()
                    }
                    ListRow(stringResource(R.string.packet_exchange), leading = { LeadingIcon(Icons.AutoMirrored.Filled.ReceiptLong) }) {
                        controller.openActivity(PacketLogActivity::class.java)
                    }
                }
            }
        }
    }
}
