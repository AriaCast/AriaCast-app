package com.aria.ariacast

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement

/**
 * Which speaker the running code is working for. [PacketLogger] tags every line it logs with
 * this, so a speaker's log needs no per-call bookkeeping: code that runs inside
 * `launch(DeviceContext.element(host))`, including coroutines it starts, is attributed to that
 * speaker. Code on threads outside a coroutine (a protocol client's own sender thread) is not,
 * and falls back to matching the speaker's address or name in the text.
 */
object DeviceContext {
    val current = ThreadLocal<String?>()

    fun element(host: String): ThreadContextElement<String?> = current.asContextElement(host)
}
