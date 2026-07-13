@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package top.kagg886.saltify.console.util.pipe

import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.get
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.ptr
import okio.Buffer
import okio.IOException
import okio.Sink
import okio.Source
import okio.Timeout
import okio.buffer
import platform.posix.close
import platform.posix.EPIPE
import platform.posix.errno
import platform.posix.fstat
import platform.posix.pipe
import platform.posix.read
import platform.posix.S_IFIFO
import platform.posix.S_IFMT
import platform.posix.stat
import platform.posix.write

actual object Pipe {
    actual fun create(): Pair<ReceivablePipe, SendablePipe> {
        memScoped {
            val descriptors = allocArray<IntVar>(2)
            if (pipe(descriptors) != 0) throw IOException("pipe() failed: errno=$errno")
            return fromReceivablePipe(descriptors[0].toULong()) to fromSendablePipe(descriptors[1].toULong())
        }
    }

    actual fun fromReceivablePipe(descriptor: ULong): ReceivablePipe {
        checkPipeDescriptor(descriptor)
        return UnixReceivablePipe(descriptor)
    }

    actual fun fromSendablePipe(descriptor: ULong): SendablePipe {
        checkPipeDescriptor(descriptor)
        return UnixSendablePipe(descriptor)
    }
}

@OptIn(UnsafeNumber::class)
private fun checkPipeDescriptor(descriptor: ULong) {
    if (descriptor > Int.MAX_VALUE.toULong()) throw InvalidPipeException(descriptor)
    val isPipe = memScoped {
        val status = alloc<stat>()
        fstat(descriptor.toInt(), status.ptr) == 0 && (status.st_mode and S_IFMT.toUShort()) == S_IFIFO.toUShort()
    }
    if (!isPipe) throw InvalidPipeException(descriptor)
}

private class UnixSendablePipe(override val descriptor: ULong) : SendablePipe {
    private val closeHandlers = CloseHandlers()
    private val delegate = UnixSink(descriptor, ::close).buffer()
    private var isClosed = false
    override val closed get() = isClosed
    override fun onClose(handle: () -> Unit) = closeHandlers.add(handle)
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

    override fun write(source: Buffer, byteCount: Long) {
        check(!isClosed) { "closed" }
        delegate.write(source, byteCount)
    }

    override fun flush() = delegate.flush()
}

private class UnixReceivablePipe(override val descriptor: ULong) : ReceivablePipe {
    private val closeHandlers = CloseHandlers()
    private val delegate = UnixSource(descriptor, ::close).buffer()
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

private class UnixSource(
    private val descriptor: ULong,
    private val onPipeClosed: () -> Unit,
) : Source {
    private val unsafeCursor = Buffer.UnsafeCursor()
    private var closed = false

    override fun read(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
        check(!closed) { "closed" }
        if (byteCount == 0L) return 0L

        val initialSize = sink.size
        val cursor = sink.readAndWriteUnsafe(unsafeCursor)
        val capacity = cursor.expandBuffer(minOf(byteCount, 8_192L).toInt())
        val count = minOf(byteCount, capacity.toLong()).toInt()
        val readCount = cursor.data!!.usePinned {
            read(descriptor.toInt(), it.addressOf(cursor.start), count.toULong())
        }
        cursor.resizeBuffer(initialSize + maxOf(readCount, 0))
        cursor.close()
        if (readCount < 0) throw IOException("read() failed: errno=$errno")
        return if (readCount == 0L) {
            onPipeClosed()
            -1L
        } else {
            readCount
        }
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        if (closed) return
        closed = true
        if (close(descriptor.toInt()) != 0) throw IOException("close() failed: errno=$errno")
    }
}

private class UnixSink(
    private val descriptor: ULong,
    private val onPipeClosed: () -> Unit,
) : Sink {
    private val unsafeCursor = Buffer.UnsafeCursor()
    private var closed = false

    override fun write(source: Buffer, byteCount: Long) {
        require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
        require(source.size >= byteCount) { "source.size=${source.size} < byteCount=$byteCount" }
        check(!closed) { "closed" }

        var remaining = byteCount
        while (remaining > 0L) {
            val cursor = source.readUnsafe(unsafeCursor)
            val count = minOf(remaining, cursor.next().toLong()).toInt()
            val written = cursor.data!!.usePinned {
                write(descriptor.toInt(), it.addressOf(cursor.start), count.toULong())
            }
            cursor.close()
            if (written <= 0) {
                if (errno == EPIPE) onPipeClosed()
                throw IOException("write() failed: errno=$errno")
            }
            source.skip(written)
            remaining -= written
        }
    }

    override fun flush() = Unit
    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        if (closed) return
        closed = true
        if (close(descriptor.toInt()) != 0) throw IOException("close() failed: errno=$errno")
    }
}
