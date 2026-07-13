@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package top.kagg886.saltify.console.util.pipe

import kotlinx.cinterop.*
import okio.*
import platform.windows.*

actual object Pipe {
    actual fun create(): Pair<ReceivablePipe, SendablePipe> {
        memScoped {
            val readHandle = allocPointerTo<CPointed>()
            val writeHandle = allocPointerTo<CPointed>()
            val securityAttributes = alloc<SECURITY_ATTRIBUTES> {
                nLength = sizeOf<SECURITY_ATTRIBUTES>().toUInt()
                bInheritHandle = 1
            }
            if (CreatePipe(
                    readHandle.ptr.reinterpret<CPointerVarOf<COpaquePointer>>(),
                    writeHandle.ptr.reinterpret<CPointerVarOf<COpaquePointer>>(),
                    securityAttributes.ptr,
                    0u,
                ) == 0
            ) {
                throw IOException("CreatePipe() failed: error=${GetLastError()}")
            }
            return fromReceivablePipe(readHandle.value.toDescriptor()) to fromSendablePipe(writeHandle.value.toDescriptor())
        }
    }

    actual fun fromReceivablePipe(descriptor: ULong): ReceivablePipe {
        checkPipeHandle(descriptor)
        return WindowsReceivablePipe(descriptor)
    }

    actual fun fromSendablePipe(descriptor: ULong): SendablePipe {
        checkPipeHandle(descriptor)
        return WindowsSendablePipe(descriptor)
    }
}

private fun COpaquePointer?.toDescriptor(): ULong = requireNotNull(this).rawValue.toLong().toULong()
private fun ULong.toHandle(): COpaquePointer? = toLong().toCPointer()

private fun checkPipeHandle(descriptor: ULong) {
    if (GetFileType(descriptor.toHandle()) != FILE_TYPE_PIPE.toUInt()) throw InvalidPipeException(descriptor)
}

private class WindowsSendablePipe(override val descriptor: ULong) : SendablePipe {
    private val closeHandlers = CloseHandlers()
    private val delegate = WindowsSink(descriptor, ::close).buffer()
    private var isClosed = false
    override val closed get() = isClosed
    override fun onClose(handle: () -> Unit) = closeHandlers.add(handle)
    override fun write(source: Buffer, byteCount: Long) {
        check(!isClosed) { "closed" }
        delegate.write(source, byteCount)
    }

    override fun flush() = delegate.flush()
    override fun timeout() = delegate.timeout()
    override fun close() {
        if (isClosed) return
        isClosed = true
        try {
            closeHandlers.notifyClosed()
        } finally {
            delegate.close()
        }
    }
}

private class WindowsReceivablePipe(override val descriptor: ULong) : ReceivablePipe {
    private val closeHandlers = CloseHandlers()
    private val delegate = WindowsSource(descriptor, ::close).buffer()
    private var isClosed = false
    override val closed get() = isClosed
    override fun onClose(handle: () -> Unit) = closeHandlers.add(handle)
    override fun read(sink: Buffer, byteCount: Long): Long {
        check(!isClosed) { "closed" }
        return delegate.read(sink, byteCount)
    }

    override fun timeout() = delegate.timeout()
    override fun close() {
        if (isClosed) return
        isClosed = true
        try {
            closeHandlers.notifyClosed()
        } finally {
            delegate.close()
        }
    }
}

private class WindowsSource(
    private val descriptor: ULong,
    private val onPipeClosed: () -> Unit,
) : Source {
    private val unsafeCursor = Buffer.UnsafeCursor()
    private var closed = false

    override fun read(sink: Buffer, byteCount: Long): Long = memScoped {
        require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
        check(!closed) { "closed" }
        if (byteCount == 0L) return 0L

        val initialSize = sink.size
        val cursor = sink.readAndWriteUnsafe(unsafeCursor)
        val capacity = cursor.expandBuffer(minOf(byteCount, 8_192L).toInt())
        val count = minOf(byteCount, capacity).toInt()
        val readCount = alloc<UIntVar>()
        val success = cursor.data!!.usePinned {
            ReadFile(descriptor.toHandle(), it.addressOf(cursor.start), count.toUInt(), readCount.ptr, null)
        }
        cursor.resizeBuffer(initialSize + if (success != 0) readCount.value.toLong() else 0L)
        cursor.close()
        if (success == 0) {
            val error = GetLastError()
            if (error == ERROR_BROKEN_PIPE.toUInt()) {
                onPipeClosed()
                return -1L
            }
            throw IOException("ReadFile() failed: error=$error")
        }
        return if (readCount.value == 0u) {
            onPipeClosed()
            -1L
        } else {
            readCount.value.toLong()
        }
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        if (closed) return
        closed = true
        if (CloseHandle(descriptor.toHandle()) == 0) throw IOException("CloseHandle() failed: error=${GetLastError()}")
    }
}

private class WindowsSink(
    private val descriptor: ULong,
    private val onPipeClosed: () -> Unit,
) : Sink {
    private val unsafeCursor = Buffer.UnsafeCursor()
    private var closed = false

    override fun write(source: Buffer, byteCount: Long) = memScoped {
        require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
        require(source.size >= byteCount) { "source.size=${source.size} < byteCount=$byteCount" }
        check(!closed) { "closed" }
        var remaining = byteCount
        while (remaining > 0L) {
            val cursor = source.readUnsafe(unsafeCursor)
            val count = minOf(remaining, cursor.next().toLong()).toInt()
            val written = alloc<UIntVar>()
            val success = cursor.data!!.usePinned {
                WriteFile(descriptor.toHandle(), it.addressOf(cursor.start), count.toUInt(), written.ptr, null)
            }
            cursor.close()
            if (success == 0 || written.value == 0u) {
                val error = GetLastError()
                if (error == ERROR_BROKEN_PIPE.toUInt()) onPipeClosed()
                throw IOException("WriteFile() failed: error=$error")
            }
            source.skip(written.value.toLong())
            remaining -= written.value.toLong()
        }
    }

    override fun flush() = Unit
    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        if (closed) return
        closed = true
        if (CloseHandle(descriptor.toHandle()) == 0) throw IOException("CloseHandle() failed: error=${GetLastError()}")
    }
}
