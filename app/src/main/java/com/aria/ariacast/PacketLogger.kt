package com.aria.ariacast

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class PacketDirection { IN, OUT }
enum class PacketType { AUDIO, CONTROL, METADATA, STATS, HANDSHAKE }

data class PacketLog(
    var timestamp: String,
    val direction: PacketDirection,
    val type: PacketType,
    val message: String,
    val payloadSize: Int = 0,
    var count: Int = 1
)

object PacketLogger {
    private val _logs = mutableListOf<PacketLog>()
    val logs: List<PacketLog> get() = synchronized(_logs) { _logs.toList() }

    private val _logFlow = MutableSharedFlow<PacketLog>(extraBufferCapacity = 100)
    val logFlow = _logFlow.asSharedFlow()

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun log(direction: PacketDirection, type: PacketType, message: String, payloadSize: Int = 0) {
        val timestamp = dateFormat.format(Date())
        val entry = PacketLog(
            timestamp = timestamp,
            direction = direction,
            type = type,
            message = message,
            payloadSize = payloadSize
        )
        
        synchronized(_logs) {
            val lastLog = _logs.firstOrNull()
            if (lastLog != null && 
                lastLog.direction == direction && 
                lastLog.type == type && 
                lastLog.message == message) {
                lastLog.count++
                lastLog.timestamp = timestamp
                // We still emit it to let the UI know something happened, 
                // but we need a way to distinguish between "new" and "update".
                // For simplicity, we'll let the adapter handle the merging logic 
                // since it maintains its own list anyway.
            } else {
                _logs.add(0, entry)
                if (_logs.size > 500) _logs.removeAt(_logs.size - 1)
            }
        }
        _logFlow.tryEmit(entry)
    }
    
    fun clear() {
        synchronized(_logs) {
            _logs.clear()
        }
    }
}
