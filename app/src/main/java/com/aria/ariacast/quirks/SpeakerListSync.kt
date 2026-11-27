package com.aria.ariacast.quirks

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.aria.ariacast.AudioCastService
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps the list of known speakers up to date by reading speakers.json from the project's
 * AriaCast/known-speakers repository. The only thing sent is an ordinary request for a public file; nothing about the
 * user or their speakers leaves the phone.
 *
 * The file is public and changes what the app does, so a download is treated as untrusted: HTTPS
 * only, no redirects, a size limit, and it must pass [KnownSpeakersFile.parse] and have a higher
 * version than the list in use, or it is ignored.
 */
object SpeakerListSync {
    const val URL = "https://raw.githubusercontent.com/AriaCast/known-speakers/main/speakers.json"
    const val KEY_AUTO = "speakers_sync_auto"
    const val KEY_LAST_TRY = "speakers_sync_last_try"
    const val KEY_LAST_OK = "speakers_sync_last_ok"
    const val MIN_INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val TIMEOUT_MS = 10_000
    private const val TAG = "SpeakerListSync"

    sealed interface Outcome {
        data class Updated(val version: Int) : Outcome
        data class UpToDate(val version: Int) : Outcome
        data class Failed(val why: String) : Outcome
    }

    /** What a downloaded text means for the list in use. */
    sealed interface Verdict {
        data object Invalid : Verdict
        data class NotNewer(val version: Int) : Verdict
        data class Newer(val file: KnownSpeakersFile) : Verdict
    }

    fun evaluate(text: String, currentVersion: Int): Verdict {
        val file = KnownSpeakersFile.parse(text) ?: return Verdict.Invalid
        return if (file.version > currentVersion) Verdict.Newer(file) else Verdict.NotNewer(file.version)
    }

    fun isEnabled(prefs: SharedPreferences) = prefs.getBoolean(KEY_AUTO, true)

    /** Due when a day has passed since the last try; a clock that went backwards counts as due. */
    fun isDue(lastTryMs: Long, nowMs: Long) = lastTryMs <= 0 || nowMs < lastTryMs || nowMs - lastTryMs >= MIN_INTERVAL_MS

    /** Checks for a newer list if the user allows it and a day has passed. */
    suspend fun syncIfDue(context: Context) {
        val prefs = context.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        if (!isEnabled(prefs) || !isDue(prefs.getLong(KEY_LAST_TRY, 0), System.currentTimeMillis())) return
        sync(context)
    }

    /** Downloads the list now and uses it if it is newer. */
    suspend fun sync(context: Context, url: String = URL, requireHttps: Boolean = true): Outcome = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        prefs.edit().putLong(KEY_LAST_TRY, now).apply()
        val text = try {
            fetch(url, requireHttps)
        } catch (e: IOException) {
            Log.w(TAG, "Could not read the list: ${e.message}")
            return@withContext Outcome.Failed(e.message ?: "network error")
        }
        when (val verdict = evaluate(text, KnownSpeakersStore.current.version)) {
            Verdict.Invalid -> Outcome.Failed("the file is not a valid list")
            is Verdict.NotNewer -> {
                prefs.edit().putLong(KEY_LAST_OK, now).apply()
                Outcome.UpToDate(KnownSpeakersStore.current.version)
            }
            is Verdict.Newer -> {
                KnownSpeakersStore.update(context, text)
                prefs.edit().putLong(KEY_LAST_OK, now).apply()
                Outcome.Updated(verdict.file.version)
            }
        }
    }

    /** One GET with no redirects and a size limit. */
    internal fun fetch(url: String, requireHttps: Boolean = true): String {
        val target = try { URL(url) } catch (e: java.net.MalformedURLException) { throw IOException("bad address") }
        if (requireHttps && target.protocol != "https") throw IOException("not https")
        val connection = target.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "AriaCast")
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            if (connection.contentLengthLong > KnownSpeakersFile.MAX_BYTES) throw IOException("file too big")
            return connection.inputStream.use { readBounded(it, KnownSpeakersFile.MAX_BYTES) }
        } finally {
            connection.disconnect()
        }
    }

    internal fun readBounded(input: InputStream, max: Int): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > max) throw IOException("file too big")
            out.write(buffer, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
