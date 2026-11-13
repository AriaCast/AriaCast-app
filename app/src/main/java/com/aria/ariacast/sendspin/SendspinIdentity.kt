package com.aria.ariacast.sendspin

import android.content.Context
import com.aria.ariacast.AudioCastService
import org.json.JSONObject
import java.security.SecureRandom

data class PairedServer(val serverId: String, val name: String, val lastUsed: Long)

/**
 * Persistent Sendspin identity and pairing records, in the prefs file that is excluded from
 * backups (the private key and PSKs are secrets). Generated once per install from a CSPRNG.
 */
class SendspinIdentity(context: Context) : SendspinStore {
    private val prefs = context.applicationContext.getSharedPreferences(AudioCastService.SECURE_PREFS_NAME, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    override val clientKeys: Noise.KeyPair by lazy {
        val stored = prefs.getString(KEY_PRIV, null)?.let(SendspinCodec::unb64url)
        stored?.let(Noise.KeyPair::fromPrivate) ?: Noise.KeyPair.generate().also {
            prefs.edit().putString(KEY_PRIV, SendspinCodec.b64url(it.private)).apply()
        }
    }

    override val pairingPsk: ByteArray by lazy {
        prefs.getString(KEY_PAIRING_PSK, null)?.let(SendspinCodec::unb64url)
            ?: ByteArray(32).also { random.nextBytes(it); prefs.edit().putString(KEY_PAIRING_PSK, SendspinCodec.b64url(it)).apply() }
    }

    val clientId: String get() = SendspinCodec.b64url(clientKeys.public)

    /** The version-0 pairing token the operator types or scans into the server. */
    val pairingToken: String get() = SendspinCodec.pairingPskToken(clientKeys.public, pairingPsk)

    override var unpairedAccess: Boolean
        get() = prefs.getBoolean(KEY_UNPAIRED, false)
        set(v) { prefs.edit().putBoolean(KEY_UNPAIRED, v).apply() }

    override var lastPlaybackServer: String?
        get() = prefs.getString(KEY_LAST_PLAYBACK, null)
        set(v) { prefs.edit().putString(KEY_LAST_PLAYBACK, v).apply() }

    override var volume: Int
        get() = prefs.getInt(KEY_VOLUME, 70)
        set(v) { prefs.edit().putInt(KEY_VOLUME, v).apply() }

    override var muted: Boolean
        get() = prefs.getBoolean(KEY_MUTED, false)
        set(v) { prefs.edit().putBoolean(KEY_MUTED, v).apply() }

    private fun load(): JSONObject = JSONObject(prefs.getString(KEY_RECORDS, "{}") ?: "{}")

    override fun longTermPsk(serverId: String): ByteArray? =
        load().optJSONObject(serverId)?.optString("psk")?.takeIf { it.isNotEmpty() }?.let(SendspinCodec::unb64url)

    override fun records(): List<Pair<String, ByteArray>> {
        val o = load()
        return o.keys().asSequence().mapNotNull { k -> o.optJSONObject(k)?.optString("psk")?.let { k to SendspinCodec.unb64url(it) } }.toList()
    }

    fun pairedServers(): List<PairedServer> {
        val o = load()
        return o.keys().asSequence().map { k ->
            val r = o.getJSONObject(k)
            PairedServer(k, r.optString("name", "Sendspin server"), r.optLong("used"))
        }.sortedByDescending { it.lastUsed }.toList()
    }

    @Synchronized
    override fun saveRecord(serverId: String, serverName: String, psk: ByteArray) {
        val o = load()
        o.put(serverId, JSONObject().put("psk", SendspinCodec.b64url(psk)).put("name", serverName).put("used", System.currentTimeMillis()))
        // Keep at most MAX_RECORDS: evict the least recently used (never the one just saved).
        while (o.length() > MAX_RECORDS) {
            val oldest = o.keys().asSequence().filter { it != serverId }.minByOrNull { o.getJSONObject(it).optLong("used") } ?: break
            o.remove(oldest)
        }
        prefs.edit().putString(KEY_RECORDS, o.toString()).apply()
    }

    @Synchronized
    override fun removeRecord(serverId: String) {
        val o = load()
        o.remove(serverId)
        prefs.edit().putString(KEY_RECORDS, o.toString()).apply()
    }

    private companion object {
        const val KEY_PRIV = "sendspin_client_private"
        const val KEY_PAIRING_PSK = "sendspin_pairing_psk"
        const val KEY_RECORDS = "sendspin_pairing_records"
        const val KEY_UNPAIRED = "sendspin_unpaired_access"
        const val KEY_LAST_PLAYBACK = "sendspin_last_playback_server"
        const val KEY_VOLUME = "sendspin_volume"
        const val KEY_MUTED = "sendspin_muted"
        const val MAX_RECORDS = 8
    }
}
