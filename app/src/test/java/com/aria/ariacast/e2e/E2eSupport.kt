package com.aria.ariacast.e2e

import com.aria.ariacast.airplay.AirPlaySenderIdentity
import com.aria.ariacast.airplay.AirPlayStreamer
import org.junit.Assume
import java.io.File
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Helpers for the end-to-end tests that stream to real receivers started by
 * scripts/e2e/start-receivers.sh. They're skipped unless AIRPLAY_E2E=1.
 */
object E2e {
    val outDir = File(System.getenv("E2E_OUT") ?: "/tmp/airplay-e2e")

    fun assumeEnabled() {
        Assume.assumeTrue("set AIRPLAY_E2E=1 and run scripts/e2e/start-receivers.sh", System.getenv("AIRPLAY_E2E") == "1")
    }

    fun identity(name: String = "AriaCast E2E") = AirPlaySenderIdentity(
        deviceId = "02:11:22:33:44:55",
        dacpId = "A1B2C3D4E5F60718",
        activeRemote = "1234567890",
        name = name,
        model = "AriaCastTest1,1",
        pairingSeed = ByteArray(32) { (it * 7 + 3).toByte() },
        pairingId = "5D3A1C8E-0000-4000-8000-AABBCCDDEEFF"
    )

    /**
     * The "AABBCCDDEEFF" prefix of a receiver's `_raop._tcp` service name, found over
     * mDNS exactly as the app's RaopDiscovery does. It's the id the receiver signs in
     * its Apple-Response.
     */
    fun raopServicePrefix(serviceName: String): String? {
        val iface = Collections.list(NetworkInterface.getNetworkInterfaces())
            .firstOrNull { it.isUp && !it.isLoopback && it.name == "eth0" } ?: return null
        val addr = Collections.list(iface.inetAddresses).firstOrNull { it is java.net.Inet4Address } ?: return null
        val jmdns = javax.jmdns.JmDNS.create(addr)
        return try {
            jmdns.list("_raop._tcp.local.", 6000)
                .map { it.name }
                .firstOrNull { it.substringAfter('@') == serviceName }
                ?.substringBefore('@')
        } finally {
            jmdns.close()
        }
    }
}

/** Generates 44.1 kHz s16le stereo: [leftHz] on the left channel, [rightHz] on the right. */
class ToneGenerator(private val leftHz: Double, private val rightHz: Double, private val amplitude: Double = 0.5) {
    private var n = 0L

    fun next(frames: Int): ByteArray {
        val out = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val t = (n + i) / 44100.0
            val l = (sin(2 * PI * leftHz * t) * amplitude * 32767).toInt()
            val r = (sin(2 * PI * rightHz * t) * amplitude * 32767).toInt()
            out[i * 4] = l.toByte(); out[i * 4 + 1] = (l shr 8).toByte()
            out[i * 4 + 2] = r.toByte(); out[i * 4 + 3] = (r shr 8).toByte()
        }
        n += frames
        return out
    }

    /** Feeds the streamer in real time, 20 ms at a time, like the capture does. */
    fun stream(streamer: AirPlayStreamer, seconds: Double, onTick: (Double) -> Unit = {}) {
        val chunk = 882
        val start = System.nanoTime()
        var sentFrames = 0L
        val total = (seconds * 44100).toLong()
        while (sentFrames < total) {
            streamer.writePcm(next(chunk))
            sentFrames += chunk
            onTick(sentFrames / 44100.0)
            val due = start + sentFrames * 1_000_000_000L / 44100
            val wait = due - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        }
    }
}

/** Reads what a receiver wrote to its stdout capture file after [mark]. */
class PcmCapture(private val file: File) {
    private val start = if (file.exists()) file.length() else 0L

    fun read(): ShortArray {
        RandomAccessFile(file, "r").use { raf ->
            val len = (raf.length() - start).toInt().coerceAtLeast(0) and 3.inv()
            val bytes = ByteArray(len)
            raf.seek(start)
            raf.readFully(bytes)
            return ShortArray(len / 2) { ((bytes[it * 2].toInt() and 0xFF) or (bytes[it * 2 + 1].toInt() shl 8)).toShort() }
        }
    }
}

object Signal {
    fun channel(interleaved: ShortArray, ch: Int): DoubleArray =
        DoubleArray(interleaved.size / 2) { interleaved[it * 2 + ch] / 32768.0 }

    /** Trims leading/trailing near-silence. */
    fun trimSilence(x: DoubleArray, threshold: Double = 0.01): DoubleArray {
        val first = x.indexOfFirst { abs(it) > threshold }
        val last = x.indexOfLast { abs(it) > threshold }
        return if (first < 0 || last <= first) DoubleArray(0) else x.copyOfRange(first, last + 1)
    }

    /** Relative power of [freq] in [x] (Goertzel). */
    fun power(x: DoubleArray, freq: Double, sampleRate: Double = 44100.0): Double {
        val k = 2 * cos(2 * PI * freq / sampleRate)
        var s1 = 0.0; var s2 = 0.0
        for (v in x) { val s = v + k * s1 - s2; s2 = s1; s1 = s }
        return (s1 * s1 + s2 * s2 - k * s1 * s2) / (x.size.toDouble() * x.size)
    }

    fun rms(x: DoubleArray): Double = sqrt(x.sumOf { it * it } / x.size.coerceAtLeast(1))

    /** Non-silent stretches of [x], in seconds: (start, end) pairs, 10 ms resolution. */
    fun audioSegments(x: DoubleArray, sampleRate: Int = 44100): List<Pair<Double, Double>> {
        val win = sampleRate / 100
        val segs = mutableListOf<Pair<Double, Double>>()
        var start = -1
        var i = 0
        while (i + win <= x.size) {
            var peak = 0.0
            for (j in i until i + win) peak = maxOf(peak, abs(x[j]))
            if (peak >= 0.02 && start < 0) start = i
            if (peak < 0.02 && start >= 0) { segs.add(start / sampleRate.toDouble() to i / sampleRate.toDouble()); start = -1 }
            i += win
        }
        if (start >= 0) segs.add(start / sampleRate.toDouble() to i / sampleRate.toDouble())
        return segs
    }

    /** Longest run of samples (in frames) whose 10 ms windows are silent, inside [x]. */
    fun longestDropoutMs(x: DoubleArray, sampleRate: Int = 44100): Int {
        val win = sampleRate / 100
        var longest = 0; var current = 0
        var i = 0
        while (i + win <= x.size) {
            var peak = 0.0
            for (j in i until i + win) peak = maxOf(peak, abs(x[j]))
            if (peak < 0.02) { current += 10; longest = maxOf(longest, current) } else current = 0
            i += win
        }
        return longest
    }
}

/** Collects shairport-sync's UDP metadata packets: 4-byte type, 4-byte code, payload. */
class MetadataCollector(bindAddress: String, port: Int) : AutoCloseable {
    data class Item(val type: String, val code: String, val data: ByteArray) {
        val text: String get() = String(data, Charsets.UTF_8)
    }

    private val socket = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByName(bindAddress), port)) }
    val items: MutableList<Item> = Collections.synchronizedList(mutableListOf())
    @Volatile private var running = true

    init {
        socket.soTimeout = 200
        thread(isDaemon = true, name = "metadata-$port") {
            val buf = ByteArray(70_000)
            while (running) {
                val p = DatagramPacket(buf, buf.size)
                try { socket.receive(p) } catch (e: Exception) { continue }
                if (p.length >= 8) {
                    items.add(Item(String(buf, 0, 4, Charsets.US_ASCII), String(buf, 4, 4, Charsets.US_ASCII), buf.copyOfRange(8, p.length)))
                }
            }
        }
    }

    fun find(code: String): Item? = synchronized(items) { items.lastOrNull { it.code == code } }

    fun waitFor(code: String, timeoutMs: Long = 5000): Item? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            find(code)?.let { return it }
            Thread.sleep(50)
        }
        return null
    }

    override fun close() { running = false; socket.close() }
}
