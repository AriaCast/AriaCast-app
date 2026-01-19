package com.aria.ariacast

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class PacketDirection { IN, OUT }
enum class PacketType { AUDIO, CONTROL, METADATA, STATS, HANDSHAKE }

data class PacketLog(
    val timestamp: String,
    val direction: PacketDirection,
    val type: PacketType,
    val message: String,
    val payloadSize: Int = 0
)

object PacketLogger {
    private val _logs = mutableListOf<PacketLog>()
    val logs: List<PacketLog> get() = _logs

    private val _logFlow = MutableSharedFlow<PacketLog>(extraBufferCapacity = 100)
    val logFlow = _logFlow.asSharedFlow()

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun log(direction: PacketDirection, type: PacketType, message: String, payloadSize: Int = 0) {
        val entry = PacketLog(
            timestamp = dateFormat.format(Date()),
            direction = direction,
            type = type,
            message = message,
            payloadSize = payloadSize
        )
        synchronized(_logs) {
            _logs.add(0, entry)
            if (_logs.size > 500) _logs.removeAt(_logs.size - 1)
        }
        _logFlow.tryEmit(entry)
    }
    
    fun clear() {
        synchronized(_logs) {
            _logs.clear()
        }
    }
}
