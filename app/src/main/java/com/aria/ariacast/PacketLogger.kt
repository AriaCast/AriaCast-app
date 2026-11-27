package com.aria.ariacast

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class PacketDirection { IN, OUT }
enum class PacketType { AUDIO, CONTROL, METADATA, STATS, HANDSHAKE }

data class PacketLog(
    var timestamp: String,
    val direction: PacketDirection,
    val type: PacketType,
    val message: String,
    val payloadSize: Int = 0,
    var count: Int = 1,
    /** Host of the speaker this line is about, when it is about one (used by "Report speaker"). */
    val device: String? = null,
    /** Order the line was logged in; repeats of a line keep the number of its first occurrence. */
    val seq: Long = 0,
)

object PacketLogger {
    /** Lines kept for the packet log screen, all speakers together, newest first. */
    const val GLOBAL_LIMIT = 500

    /** Lines kept per speaker, so a chatty speaker cannot push another one's history out. */
    const val DEVICE_LIMIT = 400

    /** Speakers whose history is kept; the least recently logged is dropped beyond this. */
    private const val DEVICE_COUNT_LIMIT = 32

    private val _logs = mutableListOf<PacketLog>()
    val logs: List<PacketLog> get() = synchronized(_logs) { _logs.toList() }

    // Newest first, access-ordered so the speaker that has been quiet longest is the one dropped.
    private val perDevice = object : LinkedHashMap<String, ArrayDeque<PacketLog>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArrayDeque<PacketLog>>) = size > DEVICE_COUNT_LIMIT
    }

    private val sequence = AtomicLong()

    private val _logFlow = MutableSharedFlow<PacketLog>(extraBufferCapacity = 100)
    val logFlow = _logFlow.asSharedFlow()

    // SimpleDateFormat is not thread-safe; log() is called concurrently from multiple
    // Dispatchers.IO threads (one audio/control/stats session per cast destination),
    // so each thread needs its own formatter instance rather than sharing one.
    private val dateFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    }

    /**
     * [device] defaults to the speaker the calling code is working for (see [DeviceContext]), so
     * lines logged inside a speaker's session are attributed to it without saying so.
     */
    fun log(
        direction: PacketDirection,
        type: PacketType,
        message: String,
        payloadSize: Int = 0,
        device: String? = DeviceContext.current.get(),
    ) {
        val timestamp = dateFormat.get()!!.format(Date())
        val entry = PacketLog(
            timestamp = timestamp,
            direction = direction,
            type = type,
            message = message,
            payloadSize = payloadSize,
            device = device,
            seq = sequence.incrementAndGet(),
        )

        synchronized(_logs) {
            val lastLog = _logs.firstOrNull()
            if (lastLog != null && lastLog.sameLine(entry)) {
                lastLog.count++
                lastLog.timestamp = timestamp
                // We still emit it to let the UI know something happened,
                // but we need a way to distinguish between "new" and "update".
                // For simplicity, we'll let the adapter handle the merging logic
                // since it maintains its own list anyway.
            } else {
                _logs.add(0, entry)
                if (_logs.size > GLOBAL_LIMIT) _logs.removeAt(_logs.size - 1)
            }

            if (device != null) {
                val ring = perDevice.getOrPut(device) { ArrayDeque() }
                val last = ring.peekFirst()
                if (last != null && last.sameLine(entry)) {
                    last.count++
                    last.timestamp = timestamp
                } else {
                    // A copy, because repeats are counted per list and each list counts its own.
                    ring.addFirst(entry.copy())
                    if (ring.size > DEVICE_LIMIT) ring.removeLast()
                }
            }
        }
        _logFlow.tryEmit(entry)
    }

    /**
     * Every line about one speaker, newest first: its own history plus untagged lines (written by
     * threads outside its session) that name its address or its name.
     */
    fun logsFor(host: String, name: String): List<PacketLog> = synchronized(_logs) {
        val own = perDevice[host]?.map { it.copy() } ?: emptyList()
        val mentioned = _logs.filter { line ->
            line.device == null &&
                ((host.isNotEmpty() && line.message.contains(host)) || (name.isNotEmpty() && line.message.contains(name)))
        }.map { it.copy() }
        (own + mentioned).sortedByDescending { it.seq }
    }

    fun clear() {
        synchronized(_logs) {
            _logs.clear()
            perDevice.clear()
        }
    }

    private fun PacketLog.sameLine(other: PacketLog) =
        direction == other.direction && type == other.type && message == other.message && device == other.device
}
