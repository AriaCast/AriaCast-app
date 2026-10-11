package com.aria.ariacast.airplay

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/** One RTSP/HTTP message (request or response) as seen on the wire. */
class RtspMessage(
    val startLine: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray
) {
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    val isResponse: Boolean get() = startLine.startsWith("RTSP/") || startLine.startsWith("HTTP/")

    /** Status code for responses, 0 otherwise. */
    val code: Int get() = if (isResponse) startLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0 else 0

    val reason: String get() = if (isResponse) startLine.split(' ', limit = 3).getOrNull(2) ?: "" else ""

    /** Method of a request ("POST", "GET_PARAMETER"...). */
    val method: String get() = startLine.substringBefore(' ')

    /** URI/path of a request. */
    val uri: String get() = startLine.split(' ').getOrNull(1) ?: ""

    val isSuccess: Boolean get() = code in 200..299

    override fun toString(): String = "$startLine (${body.size} bytes)"
}

/** Minimal RTSP/1.0 + HTTP/1.1 framing (headers terminated by CRLFCRLF, Content-Length body). */
object RtspCodec {
    private const val MAX_HEADER_BYTES = 64 * 1024
    private const val MAX_BODY_BYTES = 16 * 1024 * 1024

    fun read(input: InputStream): RtspMessage? {
        val lines = mutableListOf<String>()
        val line = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (lines.isEmpty() && line.size() == 0) return null
                throw IOException("connection closed mid-message")
            }
            if (++total > MAX_HEADER_BYTES) throw IOException("RTSP header too large")
            if (b == '\n'.code) {
                val text = line.toString("UTF-8").trimEnd('\r')
                line.reset()
                if (text.isEmpty()) {
                    if (lines.isEmpty()) continue // tolerate stray CRLF between messages
                    break
                }
                lines.add(text)
            } else {
                line.write(b)
            }
        }
        val headers = lines.drop(1).mapNotNull { h ->
            val idx = h.indexOf(':')
            if (idx <= 0) null else h.substring(0, idx).trim() to h.substring(idx + 1).trim()
        }
        val length = headers.firstOrNull { it.first.equals("Content-Length", true) }?.second?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY_BYTES) throw IOException("bad Content-Length $length")
        val body = ByteArray(length)
        readFully(input, body)
        return RtspMessage(lines[0], headers, body)
    }

    fun encode(startLine: String, headers: List<Pair<String, String>>, body: ByteArray?): ByteArray {
        val sb = StringBuilder()
        sb.append(startLine).append("\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        if (body != null && headers.none { it.first.equals("Content-Length", true) }) {
            sb.append("Content-Length: ").append(body.size).append("\r\n")
        }
        sb.append("\r\n")
        val head = sb.toString().toByteArray(Charsets.UTF_8)
        if (body == null || body.isEmpty()) return head
        return head + body
    }
}

/** RFC 2617 Digest authentication as used by password-protected AirPlay receivers. */
class DigestAuth(private val username: String, private val password: String) {
    private var realm: String? = null
    private var nonce: String? = null
    var upperCaseHex = false

    val isReady: Boolean get() = realm != null && nonce != null

    /** Consumes a WWW-Authenticate challenge; returns false if it isn't a Digest one. */
    fun challenge(header: String?): Boolean {
        if (header == null || !header.trim().startsWith("Digest", ignoreCase = true)) return false
        realm = param(header, "realm") ?: return false
        nonce = param(header, "nonce") ?: return false
        return true
    }

    fun authorization(method: String, uri: String): String? {
        val r = realm ?: return null
        val n = nonce ?: return null
        val ha1 = md5Hex("$username:$r:$password")
        val ha2 = md5Hex("$method:$uri")
        val response = md5Hex("$ha1:$n:$ha2")
        return "Digest username=\"$username\", realm=\"$r\", nonce=\"$n\", uri=\"$uri\", response=\"$response\""
    }

    private fun md5Hex(s: String): String {
        val hex = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return if (upperCaseHex) hex.uppercase() else hex
    }

    private fun param(header: String, name: String): String? =
        Regex("$name=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)
}

class RtspException(message: String, val code: Int = 0) : IOException(message)

/**
 * A client RTSP connection to an AirPlay receiver. Requests are strictly
 * request/response and serialised; once pairing completes [enableEncryption]
 * switches the stream to HAP framing transparently.
 */
class RtspConnection(
    val host: String,
    val port: Int,
    private val connectTimeoutMs: Int = 5000,
    private val readTimeoutMs: Int = 10000
) {
    private val socket = Socket()
    private lateinit var rawIn: InputStream
    private lateinit var rawOut: OutputStream
    private var input: InputStream? = null
    private var cipher: HapCipher? = null
    private var cseq = 0
    private val lock = Any()

    /** Headers added to every request (User-Agent, DACP-ID, Session...). */
    val defaultHeaders = LinkedHashMap<String, String>()

    /** Set for password-protected receivers; consulted when a request comes back 401. */
    var digest: DigestAuth? = null

    @Volatile var lastActivityNanos = System.nanoTime()
        private set

    val localAddress: InetAddress get() = socket.localAddress
    val remoteAddress: InetAddress get() = socket.inetAddress
    val isEncrypted: Boolean get() = cipher != null
    val isConnected: Boolean get() = socket.isConnected && !socket.isClosed

    fun connect() {
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket.tcpNoDelay = true
        socket.soTimeout = readTimeoutMs
        socket.keepAlive = true
        rawIn = BufferedInputStream(socket.getInputStream())
        rawOut = socket.getOutputStream()
        input = rawIn
    }

    /** Local address formatted for use inside an rtsp:// URL. */
    val localUrlHost: String
        get() {
            val a = localAddress.hostAddress ?: "0.0.0.0"
            return if (a.contains(':')) "[${a.substringBefore('%')}]" else a
        }

    fun enableEncryption(c: HapCipher) {
        synchronized(lock) {
            cipher = c
            input = c.decryptingStream(rawIn)
        }
    }

    fun setReadTimeout(ms: Int) { socket.soTimeout = ms }

    /**
     * Sends a request and returns the response. A 401 carrying a Digest challenge
     * is retried once with credentials when [digest] is set.
     */
    fun request(
        method: String,
        uri: String,
        body: ByteArray? = null,
        contentType: String? = null,
        headers: Map<String, String> = emptyMap(),
        protocol: String = "RTSP/1.0"
    ): RtspMessage {
        synchronized(lock) {
            var response = send(method, uri, body, contentType, headers, protocol)
            val auth = digest
            if (response.code == 401 && auth != null) {
                val challenge = response.header("WWW-Authenticate")
                if (auth.challenge(challenge)) {
                    response = send(method, uri, body, contentType, headers, protocol)
                    if (response.code == 401) {
                        // Some (older Apple) receivers compute the digest with upper-case hex.
                        auth.upperCaseHex = !auth.upperCaseHex
                        auth.challenge(response.header("WWW-Authenticate") ?: challenge)
                        response = send(method, uri, body, contentType, headers, protocol)
                    }
                }
            }
            return response
        }
    }

    private fun send(
        method: String,
        uri: String,
        body: ByteArray?,
        contentType: String?,
        headers: Map<String, String>,
        protocol: String
    ): RtspMessage {
        val all = ArrayList<Pair<String, String>>()
        all.add("CSeq" to (++cseq).toString())
        defaultHeaders.forEach { (k, v) -> if (headers.keys.none { it.equals(k, true) }) all.add(k to v) }
        headers.forEach { (k, v) -> all.add(k to v) }
        digest?.takeIf { it.isReady }?.authorization(method, uri)?.let { all.add("Authorization" to it) }
        if (contentType != null) all.add("Content-Type" to contentType)
        if (body != null) all.add("Content-Length" to body.size.toString())
        val bytes = RtspCodec.encode("$method $uri $protocol", all, body)
        val c = cipher
        rawOut.write(if (c != null) c.encrypt(bytes) else bytes)
        rawOut.flush()
        lastActivityNanos = System.nanoTime()
        while (true) {
            val msg = RtspCodec.read(input ?: throw IOException("not connected"))
                ?: throw RtspException("$method $uri: connection closed by receiver")
            if (!msg.isResponse) {
                // Receivers don't normally send requests on the control channel; answer
                // politely so they don't stall, then keep waiting for our response.
                replyOk(msg)
                continue
            }
            return msg
        }
    }

    private fun replyOk(request: RtspMessage) {
        val headers = listOfNotNull(request.header("CSeq")?.let { "CSeq" to it }, "Content-Length" to "0")
        val bytes = RtspCodec.encode("RTSP/1.0 200 OK", headers, null)
        val c = cipher
        rawOut.write(if (c != null) c.encrypt(bytes) else bytes)
        rawOut.flush()
    }

    fun close() {
        try { socket.close() } catch (_: Exception) {}
    }
}
