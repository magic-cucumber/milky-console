@file:OptIn(ExperimentalForeignApi::class)

package top.kagg886.milky.console.util.pipe

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import okio.Buffer
import okio.IOException
import okio.Timeout
import platform.posix.EBADF
import platform.posix.EINTR
import platform.posix.EPIPE
import platform.posix.close
import platform.posix.errno
import platform.posix.pipe
import platform.posix.read
import platform.posix.strerror
import platform.posix.write

actual fun IPCAnonymousPipe.Companion.create(): IPCAnonymousPipe = memScoped {
    val descriptors = allocArray<IntVar>(2)
    if (pipe(descriptors) != 0) {
        throw errnoIOException("pipe", errno)
    }

    UnixIPCPipe(
        sendFD = descriptors[1].toULong(),
        receiveFD = descriptors[0].toULong(),
    )
}

data class UnixIPCPipe(val sendFD: ULong, val receiveFD: ULong) : IPCAnonymousPipe {
    override val sink: IPCAnonymousPipeSink = IPCAnonymousPipe.fromSink(sendFD)
    override val source: IPCAnonymousPipeSource = IPCAnonymousPipe.fromSource(receiveFD)
}

actual fun IPCAnonymousPipe.Companion.fromSource(fd: ULong): IPCAnonymousPipeSource = UnixPipeSource(fd)

actual fun IPCAnonymousPipe.Companion.fromSink(fd: ULong): IPCAnonymousPipeSink = UnixPipeSink(fd)

private class UnixPipeSource(
    override val fd: ULong,
) : IPCAnonymousPipeSource {
    override var closed: Boolean = false
        private set

    private val descriptor = fd.toInt()
    private val unsafeCursor = Buffer.UnsafeCursor()

    override fun read(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
        if (closed) throw brokenPipe("read", "pipe source is closed")
        if (byteCount == 0L) return 0L

        val initialSize = sink.size
        val cursor = sink.readAndWriteUnsafe(unsafeCursor)
        try {
            val addedCapacity = cursor.expandBuffer(minOf(byteCount, 1024L).toInt())
            val attemptCount = minOf(byteCount, addedCapacity)

            var readError = 0
            val bytesRead = cursor.data!!.usePinned { pinned ->
                while (true) {
                    val result = read(
                        descriptor,
                        pinned.addressOf(cursor.start),
                        attemptCount.toULong(),
                    )
                    if (result >= 0L) return@usePinned result
                    val error = errno
                    if (error != EINTR) {
                        readError = error
                        return@usePinned result
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                -1L
            }

            cursor.resizeBuffer(initialSize + maxOf(bytesRead, 0L))

            if (bytesRead > 0L) return bytesRead
            if (bytesRead == 0L) {
                markClosed()
                return -1
            }

            val error = readError
            if (error == EPIPE || error == EBADF) {
                markClosed()
                return -1
            }
            throw errnoIOException("read", error)
        } finally {
            cursor.close()
        }
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        if (closed) return
        closed = true
        if (close(descriptor) != 0) {
            val error = errno
            if (error == EBADF) throw brokenPipe("close", errnoMessage(error))
            throw errnoIOException("close", error)
        }
    }

    private fun markClosed() {
        if (closed) return
        closed = true
        close(descriptor)
    }
}

private class UnixPipeSink(
    override val fd: ULong,
) : IPCAnonymousPipeSink {
    override var closed: Boolean = false
        private set

    private val descriptor = fd.toInt()
    private val unsafeCursor = Buffer.UnsafeCursor()

    override fun write(source: Buffer, byteCount: Long) {
        require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
        require(source.size >= byteCount) { "source.size=${source.size} < byteCount=$byteCount" }
        if (closed) throw brokenPipe("write", "pipe sink is closed")

        var remaining = byteCount
        while (remaining > 0L) {
            val cursor = source.readUnsafe(unsafeCursor)
            val bytesWritten: Long
            var writeError = 0
            try {
                val readableCount = cursor.next()
                val attemptCount = minOf(remaining, readableCount.toLong())
                bytesWritten = cursor.data!!.usePinned { pinned ->
                    while (true) {
                        val result = write(
                            descriptor,
                            pinned.addressOf(cursor.start),
                            attemptCount.toULong(),
                        )
                        if (result >= 0L) return@usePinned result
                        val error = errno
                        if (error != EINTR) {
                            writeError = error
                            return@usePinned result
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    -1L
                }
            } finally {
                cursor.close()
            }

            if (bytesWritten <= 0L) {
                val error = writeError
                if (error == EPIPE || error == EBADF) {
                    markClosed()
                    throw brokenPipe("write", errnoMessage(error))
                }
                throw errnoIOException("write", error)
            }

            source.skip(bytesWritten)
            remaining -= bytesWritten
        }
    }

    override fun flush() {
        if (closed) throw brokenPipe("flush", "pipe sink is closed")
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        if (closed) return
        closed = true
        if (close(descriptor) != 0) {
            val error = errno
            if (error == EBADF) throw brokenPipe("close", errnoMessage(error))
            throw errnoIOException("close", error)
        }
    }

    private fun markClosed() {
        if (closed) return
        closed = true
        close(descriptor)
    }
}

private fun brokenPipe(operation: String, detail: String): BrokenPipeException =
    BrokenPipeException("$operation failed: $detail", null)

private fun errnoIOException(operation: String, error: Int): IOException =
    IOException("$operation failed: ${errnoMessage(error)}")

private fun errnoMessage(error: Int): String =
    strerror(error)?.toKString() ?: "errno $error"
