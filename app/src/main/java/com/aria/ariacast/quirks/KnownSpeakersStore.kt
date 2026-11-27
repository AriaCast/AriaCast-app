package com.aria.ariacast.quirks

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The list of known speakers in use: the last one downloaded from the project (see
 * [SpeakerListSync]), kept on the phone. The app ships no copy, so before the first download the
 * list is empty. A downloaded list only ever replaces the one in use if it is valid and has a
 * higher version, so a bad download changes nothing.
 */
object KnownSpeakersStore {
    private const val TAG = "KnownSpeakers"
    private const val CACHE = "speakers-synced.json"

    @Volatile
    var current: KnownSpeakersFile = KnownSpeakersFile(0, "", emptyList())
        private set

    /** Loads the list downloaded last time, if any. Safe to call more than once. */
    @Synchronized
    fun init(context: Context) {
        if (current.speakers.isNotEmpty()) return
        val cached = cacheFile(context).takeIf { it.exists() }?.let { KnownSpeakersFile.parse(runCatching { it.readText() }.getOrDefault("")) }
        current = cached ?: KnownSpeakersFile(0, "", emptyList())
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
