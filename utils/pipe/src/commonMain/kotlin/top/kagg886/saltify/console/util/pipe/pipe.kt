package top.kagg886.saltify.console.util.pipe

import okio.Sink
import okio.Source

/**
 * ================================================
 * Author:     886kagg
 * Created on: 2026/7/13 20:50
 * ================================================
 */

interface IPipe {
    val closed: Boolean
    val descriptor: ULong

    fun onClose(handle: () -> Unit): AutoCloseable
}


interface ReceivablePipe : Source, IPipe

interface SendablePipe : Sink, IPipe

class InvalidPipeException(val descriptor: ULong) : IllegalArgumentException("Invalid pipe: $descriptor")

expect object Pipe {
    fun create(): Pair<ReceivablePipe, SendablePipe>

    fun fromReceivablePipe(descriptor: ULong): ReceivablePipe
    fun fromSendablePipe(descriptor: ULong): SendablePipe
}

internal class CloseHandlers {
    private val handlers = mutableListOf<() -> Unit>()
    private var notified = false

    fun add(handler: () -> Unit): AutoCloseable {
        if (notified) {
            handler()
            return AutoCloseable { }
        }
        handlers += handler
        return AutoCloseable { handlers -= handler }
    }

    fun notifyClosed() {
        if (notified) return
        notified = true
        val pending = handlers.toList()
        handlers.clear()
        pending.forEach { it() }
    }
}
