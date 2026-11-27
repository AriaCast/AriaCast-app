package com.aria.ariacast.quirks

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The list of known speakers in use: the one shipped inside the app, or a newer one downloaded
 * from the project (see [SpeakerListSync]). A downloaded list only ever replaces the shipped one
 * if it is valid and has a higher version, so a bad download changes nothing.
 */
object KnownSpeakersStore {
    private const val TAG = "KnownSpeakers"
    private const val ASSET = "speakers.json"
    private const val CACHE = "speakers-synced.json"

    @Volatile
    var current: KnownSpeakersFile = KnownSpeakersFile(0, "", emptyList())
        private set

    /** Which of two lists to use: the higher version, the shipped one on a tie. */
    fun choose(shipped: KnownSpeakersFile?, synced: KnownSpeakersFile?): KnownSpeakersFile =
        listOfNotNull(shipped, synced).maxWithOrNull(compareBy<KnownSpeakersFile> { it.version }.thenBy { if (it === shipped) 1 else 0 })
            ?: KnownSpeakersFile(0, "", emptyList())

    /** Loads the shipped list and the cached download. Safe to call more than once. */
    @Synchronized
    fun init(context: Context) {
        if (current.speakers.isNotEmpty()) return
        val shipped = runCatching { context.assets.open(ASSET).use { KnownSpeakersFile.parse(it.readBytes().toString(Charsets.UTF_8)) } }.getOrNull()
        val synced = cacheFile(context).takeIf { it.exists() }?.let { KnownSpeakersFile.parse(runCatching { it.readText() }.getOrDefault("")) }
        current = choose(shipped, synced)
        Log.i(TAG, "Using list v${current.version} (${current.speakers.size} entries)")
    }

    /**
     * Takes a downloaded list. Returns true if it was newer and valid and is now in use; the text
     * is then kept so the next start uses it too.
     */
    @Synchronized
    fun update(context: Context, text: String): Boolean {
        val file = KnownSpeakersFile.parse(text) ?: return false
        if (file.version <= current.version) return false
        runCatching { cacheFile(context).writeText(text) }
        current = file
        return true
    }

    /** For tests and previews. */
    fun setForTesting(file: KnownSpeakersFile) {
        current = file
    }

    private fun cacheFile(context: Context) = File(context.filesDir, CACHE)
}
