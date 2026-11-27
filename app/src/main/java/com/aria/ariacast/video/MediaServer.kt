package com.aria.ariacast.video

import com.aria.ariacast.net.startOrNull
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.routing
import java.util.UUID

/**
 * Serves one local file (a video picked by the user) to the TV over HTTP, with Range support
 * so the TV can seek. Only the registered file is reachable, under a random token.
 */
class MediaServer(private val resolver: ContentResolver, val port: Int = PORT) {
    data class Entry(val uri: Uri, val mime: String, val size: Long)

    private var engine: ApplicationEngine? = null
    @Volatile private var media: Pair<String, Entry>? = null
    @Volatile private var subtitles: Pair<String, String>? = null // token -> WebVTT text

    fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            routing {
                head("/media/{token}") {
                    val e = entryFor(call.parameters["token"]) ?: return@head call.respondText("", status = HttpStatusCode.NotFound)
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                    call.response.header(HttpHeaders.ContentLength, e.size.toString())
                    call.respondText("", ContentType.parse(e.mime))
                }
                get("/media/{token}") {
                    val e = entryFor(call.parameters["token"]) ?: return@get call.respondText("", status = HttpStatusCode.NotFound)
                    val range = RangeHeader.parse(call.request.headers[HttpHeaders.Range], e.size)
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                    // DLNA renderers look for these
                    call.response.header("transferMode.dlna.org", "Streaming")
                    call.response.header("contentFeatures.dlna.org", "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000")
                    val status = if (range != null) HttpStatusCode.PartialContent else HttpStatusCode.OK
                    val start = range?.first ?: 0L
                    val end = range?.last ?: (e.size - 1)
                    if (range != null) call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/${e.size}")
                    call.response.header(HttpHeaders.ContentLength, (end - start + 1).toString())
                    call.respondOutputStream(ContentType.parse(e.mime), status) {
                        resolver.openInputStream(e.uri)?.use { input ->
                            var skipped = 0L
                            while (skipped < start) {
                                val n = input.skip(start - skipped)
                                if (n <= 0) break
                                skipped += n
                            }
                            var remaining = end - start + 1
                            val buf = ByteArray(64 * 1024)
                            while (remaining > 0) {
                                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                                if (n < 0) break
                                write(buf, 0, n)
                                remaining -= n
                            }
                        }
                    }
                }
                get("/subs/{token}") {
                    val s = subtitles?.takeIf { it.first == call.parameters["token"]?.removeSuffix(".vtt") }
                        ?: return@get call.respondText("", status = HttpStatusCode.NotFound)
                    call.response.header("Access-Control-Allow-Origin", "*") // Cast receivers load tracks via CORS
                    call.respondText(s.second, ContentType.parse("text/vtt; charset=utf-8"))
                }
            }
        }.startOrNull("MediaServer", "Media server on port $port")
            ?: throw java.io.IOException("Port $port is in use")
    }

    private fun entryFor(token: String?): Entry? = media?.takeIf { it.first == token }?.second

    /** Registers [uri] and returns its path on this server, e.g. "/media/<token>". */
    fun publish(uri: Uri, mime: String): String {
        val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        } ?: resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        require(size > 0) { "cannot read the file size" }
        val token = UUID.randomUUID().toString()
        media = token to Entry(uri, mime, size)
        return "/media/$token"
    }

    /** Registers subtitles (SRT or WebVTT) and returns their path, e.g. "/subs/<token>.vtt". */
    fun publishSubtitles(text: String): String {
        val token = UUID.randomUUID().toString()
        subtitles = token to Subtitles.toWebVtt(text)
        return "/subs/$token.vtt"
    }

    fun stop() {
        engine?.stop(200, 500)
        engine = null
        media = null
        subtitles = null
    }

    companion object { const val PORT = 8092 }
}

/** Parses a single HTTP byte range ("bytes=a-b", "bytes=a-", "bytes=-n"). */
object RangeHeader {
    fun parse(header: String?, size: Long): LongRange? {
        if (header == null || !header.startsWith("bytes=") || size <= 0) return null
        val spec = header.removePrefix("bytes=").substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val a = spec.substring(0, dash).trim()
        val b = spec.substring(dash + 1).trim()
        return when {
            a.isEmpty() && b.isNotEmpty() -> { val n = b.toLongOrNull() ?: return null; (size - n).coerceAtLeast(0)..(size - 1) }
            a.isNotEmpty() -> {
                val start = a.toLongOrNull() ?: return null
                val end = b.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
                if (start > end || start >= size) null else start..end
            }
            else -> null
        }
    }
}

/** SRT -> WebVTT (Cast and browsers only take WebVTT). WebVTT input is returned unchanged. */
object Subtitles {
    private val SRT_TIME = Regex("""(\d{2}:\d{2}:\d{2}),(\d{3})""")

    fun toWebVtt(text: String): String {
        val clean = text.removePrefix("﻿").replace("\r\n", "\n")
        if (clean.trimStart().startsWith("WEBVTT")) return clean
        val body = clean.lines().joinToString("\n") { line -> if ("-->" in line) SRT_TIME.replace(line) { "${it.groupValues[1]}.${it.groupValues[2]}" } else line }
        return "WEBVTT\n\n$body"
    }
}
