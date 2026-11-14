package com.aria.ariacast

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aria.ariacast.ui.LeadingIcon
import com.aria.ariacast.ui.ListBlock
import com.aria.ariacast.ui.ListGroup
import com.aria.ariacast.ui.ListRow
import com.aria.ariacast.ui.ScreenList
import com.aria.ariacast.ui.StatusBanner
import com.aria.ariacast.ui.SubScreenScaffold
import com.aria.ariacast.ui.SwitchListRow
import com.aria.ariacast.ui.home.platformIcon
import com.aria.ariacast.ui.theme.AriaTheme

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import android.os.Bundle
import java.io.IOException

/**
 * Writes an ariacast://<host>[:<port>][?type=...&name=...][&ssid=...&pass=...] link to a
 * blank or rewritable NFC tag, so a phone tapping it later (MainActivity.handleDeepLink)
 * can auto-start casting - optionally after joining a Wi-Fi network first (WifiJoinManager).
 * This screen only builds and writes the link; it never joins Wi-Fi or starts casting itself.
 */
class NfcWriteActivity : ComponentActivity() {

    private lateinit var discoveryManager: DiscoveryManager
    private var nfcAdapter: NfcAdapter? = null

    // Form state, owned here so onNewIntent (a tag tapped) can read it.
    private var host by mutableStateOf("")
    private var port by mutableStateOf("")
    private var typeIndex by mutableIntStateOf(0)
    private var name by mutableStateOf("")
    private var wifi by mutableStateOf(false)
    private var ssid by mutableStateOf("")
    private var pass by mutableStateOf("")
    private var status by mutableStateOf<String?>(null)
    private var statusError by mutableStateOf(false)

    private fun fail(res: Int) { status = getString(res); statusError = true }
    private fun progress(text: String) { status = text; statusError = false }

    private data class ProtocolOption(val labelRes: Int, val typeKey: String?)

    private val protocolOptions = listOf(
        ProtocolOption(R.string.ariacast_protocol, null),
        ProtocolOption(R.string.airplay_protocol, "airplay"),
        ProtocolOption(R.string.airplay2_protocol, "airplay2"),
        ProtocolOption(R.string.dlna_protocol, "dlna"),
        ProtocolOption(R.string.google_cast_protocol, "googlecast")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        discoveryManager = DiscoveryManager(this)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) fail(R.string.nfc_not_available)
        setContent { AriaTheme { NfcWriteScreen() } }
    }

    override fun onStart() {
        super.onStart()
        discoveryManager.startDiscovery()
    }

    override fun onStop() {
        super.onStop()
        discoveryManager.stopDiscovery()
    }

    /** Prefills the manual-entry fields from an already-discovered device - the fields stay
     *  editable afterward, e.g. to override the name before writing the tag. */
    private fun applyDiscoveredServer(server: Server) {
        host = server.host
        port = server.port.toString()
        name = server.name
        val typeKey = platformToTypeKey(server.platform)
        typeIndex = protocolOptions.indexOfFirst { it.typeKey == typeKey }.coerceAtLeast(0)
    }

    private fun platformToTypeKey(platform: String?): String? = when (platform) {
        "AirPlay" -> "airplay"
        "AirPlay2" -> "airplay2"
        "DLNA" -> "dlna"
        "Google Cast" -> "googlecast"
        else -> null
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.let { adapter ->
            val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            adapter.enableForegroundDispatch(this, pendingIntent, null, null)
        }
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val tag: Tag? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        if (tag != null) writeTag(tag)
    }

    /** Builds the ariacast:// link from the form, or null if it isn't fillable yet
     *  (missing host). Uses Uri.Builder so every field is safely percent-encoded, however
     *  it's typed - the same values will round-trip through Uri.getQueryParameter() on the
     *  reading side in MainActivity.handleDeepLink(). */
    private fun buildDeepLinkUri(): Uri? {
        val h = host.trim()
        if (h.isEmpty()) return null
        val p = port.trim()
        val n = name.trim()
        val typeKey = protocolOptions[typeIndex].typeKey

        val builder = Uri.Builder()
            .scheme("ariacast")
            .authority(if (p.isNotEmpty()) "$h:$p" else h)
        if (typeKey != null) builder.appendQueryParameter("type", typeKey)
        if (n.isNotEmpty()) builder.appendQueryParameter("name", n)

        if (wifi) {
            val s = ssid.trim()
            if (s.isNotEmpty()) {
                builder.appendQueryParameter("ssid", s)
                if (pass.isNotEmpty()) builder.appendQueryParameter("pass", pass)
            }
        }
        return builder.build()
    }

    private fun writeTag(tag: Tag) {
        val typeKey = protocolOptions[typeIndex].typeKey
        if (typeKey == "dlna" && wifi) {
            fail(R.string.nfc_write_dlna_wifi_conflict)
            return
        }

        val uri = buildDeepLinkUri()
        if (uri == null) {
            fail(R.string.nfc_write_missing_host)
            return
        }

        progress(getString(R.string.nfc_write_status_writing))
        val message = NdefMessage(arrayOf(NdefRecord.createUri(uri)))

        try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                try {
                    if (!ndef.isWritable) {
                        fail(R.string.nfc_write_read_only)
                        return
                    }
                    if (ndef.maxSize < message.toByteArray().size) {
                        fail(R.string.nfc_write_too_small)
                        return
                    }
                    ndef.writeNdefMessage(message)
                    progress(getString(R.string.nfc_write_status_success, uri.host))
                } finally {
                    try { ndef.close() } catch (e: IOException) {}
                }
                return
            }

            val formatable = NdefFormatable.get(tag)
            if (formatable != null) {
                formatable.connect()
                try {
                    formatable.format(message)
                    progress(getString(R.string.nfc_write_status_success, uri.host))
                } finally {
                    try { formatable.close() } catch (e: IOException) {}
                }
                return
            }

            fail(R.string.nfc_write_unsupported_tag)
        } catch (e: IOException) {
            fail(R.string.nfc_write_failed)
        } catch (e: FormatException) {
            fail(R.string.nfc_write_failed)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun NfcWriteScreen() {
        val servers by discoveryManager.servers.collectAsState()
        var typeMenu by remember { mutableStateOf(false) }
        SubScreenScaffold(title = stringResource(R.string.nfc_write_title), onBack = ::finish) { padding ->
            ScreenList(Modifier.padding(padding)) {
                item {
                    ListGroup(title = stringResource(R.string.manual_entry)) {
                        ListBlock {
                            OutlinedTextField(host, { host = it }, label = { Text(stringResource(R.string.nfc_write_host_hint)) }, singleLine = true,
                                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.nfc_write_port_hint)) }, singleLine = true,
                                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            ExposedDropdownMenuBox(expanded = typeMenu, onExpandedChange = { typeMenu = it }) {
                                OutlinedTextField(
                                    stringResource(protocolOptions[typeIndex].labelRes), {}, readOnly = true, singleLine = true,
                                    label = { Text(stringResource(R.string.nfc_write_type_label)) },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeMenu) },
                                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                                )
                                ExposedDropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                                    protocolOptions.forEachIndexed { i, o ->
                                        DropdownMenuItem(text = { Text(stringResource(o.labelRes)) }, onClick = { typeIndex = i; typeMenu = false })
                                    }
                                }
                            }
                            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.nfc_write_name_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        SwitchListRow(stringResource(R.string.nfc_write_wifi_switch), null, wifi, { wifi = it }, Icons.Filled.Wifi)
                        if (wifi) {
                            ListBlock {
                                OutlinedTextField(ssid, { ssid = it }, label = { Text(stringResource(R.string.nfc_write_ssid_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(pass, { pass = it }, label = { Text(stringResource(R.string.nfc_write_pass_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                            }
                        }
                    }
                }
                item {
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 8.dp))
                    // What happens next, always visible at the top: ready, writing, done, or the problem.
                    StatusBanner(Icons.Filled.Nfc, status ?: stringResource(R.string.nfc_write_status_ready), null, error = statusError)
                }
                item {
                    ListGroup(Modifier.padding(top = 8.dp), title = stringResource(R.string.devices)) {
                        if (servers.isEmpty()) {
                            ListBlock { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        }
                        servers.forEach { server ->
                            ListRow(
                                headline = server.name,
                                supporting = listOfNotNull(server.platform, server.host).joinToString(" · "),
                                leading = { LeadingIcon(platformIcon(server.platform)) },
                                selected = host == server.host && port == server.port.toString(),
                                onClick = { applyDiscoveredServer(server) },
                            )
                        }
                    }
                }
            }
        }
    }
}
