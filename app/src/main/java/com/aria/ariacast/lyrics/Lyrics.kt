package com.aria.ariacast.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(val synced: List<LyricLine>, val plain: String?) {
    /** Index of the line playing at [positionMs], or -1 before the first line. */
    fun lineAt(positionMs: Long): Int {
        var lo = 0
        var hi = synced.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (synced[mid].timeMs <= positionMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}

/** Parses LRC text ("[mm:ss.xx] line"), including lines with several timestamps. */
object LrcParser {
    private val TAG = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parse(lrc: String): List<LyricLine> = lrc.lineSequence().flatMap { raw ->
        val tags = TAG.findAll(raw).toList()
        if (tags.isEmpty()) return@flatMap emptySequence()
        val text = raw.substring(tags.last().range.last + 1).trim()
        tags.asSequence().map { m ->
            val min = m.groupValues[1].toLong()
            val sec = m.groupValues[2].toLong()
            val frac = m.groupValues[3]
            val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
            LyricLine(min * 60_000 + sec * 1000 + ms, text)
        }
    }.sortedBy { it.timeMs }.toList()
}

/**
 * Synced lyrics from LRCLIB (https://lrclib.net), a free, open lyrics database.
 * Only used when the user turned lyrics on: it sends the song title, artist and album there.
 */
object LrcLib {
    private val cache = object : LinkedHashMap<String, Lyrics?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics?>?) = size > 32
    }

    suspend fun fetch(title: String, artist: String, album: String?, durationMs: Long?): Lyrics? = withContext(Dispatchers.IO) {
        val key = "$artist|$title|$album"
        synchronized(cache) { if (cache.containsKey(key)) return@withContext cache[key] }
        val params = buildList {
            add("track_name=" + enc(title))
            add("artist_name=" + enc(artist))
            album?.takeIf { it.isNotBlank() }?.let { add("album_name=" + enc(it)) }
            durationMs?.takeIf { it > 0 }?.let { add("duration=" + (it / 1000)) }
        }.joinToString("&")
        val result = runCatching {
            val c = URL("https://lrclib.net/api/get?$params").openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "AriaCast (https://github.com/AriaCast/AriaCast-app)")
            try {
                if (c.responseCode != 200) null else {
                    val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }?.let(LrcParser::parse) ?: emptyList()
                    val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
                    if (synced.isEmpty() && plain == null) null else Lyrics(synced, plain)
                }
            } finally { c.disconnect() }
        }.getOrNull()
        synchronized(cache) { cache[key] = result }
        result
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
