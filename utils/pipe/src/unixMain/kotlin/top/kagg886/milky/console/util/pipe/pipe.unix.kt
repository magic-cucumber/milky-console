@file:OptIn(ExperimentalForeignApi::class)

package top.kagg886.milky.console.util.pipe

import co.touchlab.kermit.Logger
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
import platform.posix.FD_CLOEXEC
import platform.posix.F_GETFD
import platform.posix.F_SETFD
import platform.posix.SIGPIPE
import platform.posix.SIG_IGN
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.pipe
import platform.posix.read
import platform.posix.signal
import platform.posix.strerror
import platform.posix.write

private val logger = Logger.withTag("UnixPipe")

actual fun IPCAnonymousPipe.Companion.create(): IPCAnonymousPipe = memScoped {
    logger.v { "enter create" }
    ignoreBrokenPipeSignal()
    val descriptors = allocArray<IntVar>(2)
    if (pipe(descriptors) != 0) {
        logger.e { "pipe failed; anonymous pipe not opened: errno=$errno" }
        throw errnoIOException("pipe", errno)
    }
    descriptors.forEach { descriptor ->
        val flags = fcntl(descriptor, F_GETFD)
        if (flags < 0 || fcntl(descriptor, F_SETFD, flags or FD_CLOEXEC) != 0) {
            val error = errno
            close(descriptors[0])
            close(descriptors[1])
            logger.e { "failed to set close-on-exec for pipe descriptor=$descriptor, errno=$error" }
            throw errnoIOException("fcntl(F_SETFD)", error)
        }
    }

    val pipe = UnixIPCPipe(
        sendFD = descriptors[1].toULong(),
        receiveFD = descriptors[0].toULong(),
    )
    logger.i { "opened anonymous pipe: sourceFd=${pipe.receiveFD}, sinkFd=${pipe.sendFD}" }
    logger.v { "exit create successfully: sourceFd=${pipe.receiveFD}, sinkFd=${pipe.sendFD}" }
    pipe
}

/**
 * POSIX raises SIGPIPE before [write] can return EPIPE when the read end is closed.
 * Ignore it process-wide so pipe writes follow this API's error contract instead.
 */
private fun ignoreBrokenPipeSignal() {
    logger.v { "enter ignoreBrokenPipeSignal" }
    signal(SIGPIPE, SIG_IGN)
    logger.d { "SIGPIPE handler configured: ignored=true" }
    logger.v { "exit ignoreBrokenPipeSignal" }
}

data class UnixIPCPipe(val sendFD: ULong, val receiveFD: ULong) : IPCAnonymousPipe {
    override val sink: IPCAnonymousPipeSink = IPCAnonymousPipe.fromSink(sendFD)
    override val source: IPCAnonymousPipeSource = IPCAnonymousPipe.fromSource(receiveFD)
}

actual fun IPCAnonymousPipe.Companion.fromSource(fd: ULong): IPCAnonymousPipeSource {
    logger.v { "enter fromSource: fd=$fd" }
    val source = UnixPipeSource(fd)
    logger.d { "created pipe source wrapper: fd=$fd, expected=true" }
    logger.v { "exit fromSource: fd=$fd" }
    return source
}

actual fun IPCAnonymousPipe.Companion.fromSink(fd: ULong): IPCAnonymousPipeSink {
    logger.v { "enter fromSink: fd=$fd" }
    val sink = UnixPipeSink(fd)
    logger.d { "created pipe sink wrapper: fd=$fd, expected=true" }
    logger.v { "exit fromSink: fd=$fd" }
    return sink
}

private class UnixPipeSource(
    override val fd: ULong,
) : IPCAnonymousPipeSource {
    override var closed: Boolean = false
        private set

    private val descriptor = fd.toInt()
    private val unsafeCursor = Buffer.UnsafeCursor()

    override fun read(sink: Buffer, byteCount: Long): Long {
        logger.v { "enter read: fd=$fd, descriptor=$descriptor, byteCount=$byteCount, closed=$closed, sinkSize=${sink.size}" }
        require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
        if (closed) {
            logger.e { "read failed before IO; source is closed: fd=$fd" }
            throw brokenPipe("read", "pipe source is closed")
        }
        if (byteCount == 0L) {
            logger.d { "read skipped zero-byte request: fd=$fd, expected=true" }
            logger.v { "exit read: fd=$fd, bytesRead=0" }
            return 0L
        }

        val initialSize = sink.size
        val cursor = sink.readAndWriteUnsafe(unsafeCursor)
        try {
            val addedCapacity = cursor.expandBuffer(minOf(byteCount, 1024L).toInt())
            val attemptCount = minOf(byteCount, addedCapacity)
            logger.d { "prepared read buffer: fd=$fd, initialSize=$initialSize, addedCapacity=$addedCapacity, attemptCount=$attemptCount" }

            var readError = 0
            val bytesRead = cursor.data!!.usePinned { pinned ->
                while (true) {
                    logger.v { "read syscall enter: fd=$fd, attemptCount=$attemptCount" }
                    val result = read(
                        descriptor,
                        pinned.addressOf(cursor.start),
                        attemptCount.toULong(),
                    )
                    if (result >= 0L) {
                        logger.v { "read syscall exit successfully: fd=$fd, result=$result" }
                        return@usePinned result
                    }
                    val error = errno
                    if (error != EINTR) {
                        readError = error
                        logger.v { "read syscall exit with non-retryable error: fd=$fd, errno=$error" }
                        return@usePinned result
                    }
                    logger.v { "read syscall interrupted; retrying: fd=$fd" }
                }
                @Suppress("UNREACHABLE_CODE")
                -1L
            }

            cursor.resizeBuffer(initialSize + maxOf(bytesRead, 0L))

            if (bytesRead > 0L) {
                logger.d { "read completed: fd=$fd, bytesRead=$bytesRead, sinkSize=${sink.size}, expected=true" }
                logger.v { "exit read successfully: fd=$fd, bytesRead=$bytesRead" }
                return bytesRead
            }
            if (bytesRead == 0L) {
                logger.i { "read reached EOF; closing source: fd=$fd" }
                markClosed()
                logger.v { "exit read: fd=$fd, eof=true" }
                return -1
            }

            val error = readError
            if (error == EPIPE || error == EBADF) {
                logger.i { "read hit closed peer; closing source: fd=$fd, errno=$error" }
                markClosed()
                logger.v { "exit read: fd=$fd, eof=true" }
                return -1
            }
            logger.e { "read failed: fd=$fd, errno=$error" }
            throw errnoIOException("read", error)
        } finally {
            logger.v { "closing read cursor: fd=$fd" }
            cursor.close()
        }
    }

    override fun timeout(): Timeout {
        logger.v { "enter timeout for source: fd=$fd" }
        logger.d { "source timeout resolved: fd=$fd, timeout=NONE" }
        logger.v { "exit timeout for source: fd=$fd" }
        return Timeout.NONE
    }

    override fun close() {
        logger.v { "enter source close: fd=$fd, closed=$closed" }
        if (closed) {
            logger.v { "source close skipped; already closed: fd=$fd" }
            logger.v { "exit source close: fd=$fd" }
            return
        }
        closed = true
        if (close(descriptor) != 0) {
            val error = errno
            if (error == EBADF) {
                logger.w { "source close saw bad descriptor; treating as broken pipe: fd=$fd, errno=$error" }
                throw brokenPipe("close", errnoMessage(error))
            }
            logger.e { "source close failed: fd=$fd, errno=$error" }
            throw errnoIOException("close", error)
        }
        logger.i { "closed pipe source: fd=$fd" }
        logger.v { "exit source close successfully: fd=$fd" }
    }

    private fun markClosed() {
        logger.v { "enter source markClosed: fd=$fd, closed=$closed" }
        if (closed) {
            logger.v { "source markClosed skipped; already closed: fd=$fd" }
            logger.v { "exit source markClosed: fd=$fd" }
            return
        }
        closed = true
        close(descriptor)
        logger.i { "marked pipe source closed: fd=$fd" }
        logger.v { "exit source markClosed: fd=$fd" }
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
        logger.v { "enter write: fd=$fd, descriptor=$descriptor, byteCount=$byteCount, closed=$closed, sourceSize=${source.size}" }
        require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
        require(source.size >= byteCount) { "source.size=${source.size} < byteCount=$byteCount" }
        if (closed) {
            logger.e { "write failed before IO; sink is closed: fd=$fd" }
            throw brokenPipe("write", "pipe sink is closed")
        }

        var remaining = byteCount
        logger.d { "write loop initialized: fd=$fd, remaining=$remaining, expected=${remaining >= 0L}" }
        while (remaining > 0L) {
            logger.v { "write loop enter: fd=$fd, remaining=$remaining" }
            val cursor = source.readUnsafe(unsafeCursor)
            val bytesWritten: Long
            var writeError = 0
            try {
                val readableCount = cursor.next()
                val attemptCount = minOf(remaining, readableCount.toLong())
                logger.d { "prepared write chunk: fd=$fd, readableCount=$readableCount, attemptCount=$attemptCount" }
                bytesWritten = cursor.data!!.usePinned { pinned ->
                    while (true) {
                        logger.v { "write syscall enter: fd=$fd, attemptCount=$attemptCount" }
                        val result = write(
                            descriptor,
                            pinned.addressOf(cursor.start),
                            attemptCount.toULong(),
                        )
                        if (result >= 0L) {
                            logger.v { "write syscall exit successfully: fd=$fd, result=$result" }
                            return@usePinned result
                        }
                        val error = errno
                        if (error != EINTR) {
                            writeError = error
                            logger.v { "write syscall exit with non-retryable error: fd=$fd, errno=$error" }
                            return@usePinned result
                        }
                        logger.v { "write syscall interrupted; retrying: fd=$fd" }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    -1L
                }
            } finally {
                logger.v { "closing write cursor: fd=$fd" }
                cursor.close()
            }

            if (bytesWritten <= 0L) {
                val error = writeError
                if (error == EPIPE || error == EBADF) {
                    logger.e { "write hit broken pipe: fd=$fd, errno=$error" }
                    markClosed()
                    throw brokenPipe("write", errnoMessage(error))
                }
                logger.e { "write failed: fd=$fd, errno=$error" }
                throw errnoIOException("write", error)
            }

            source.skip(bytesWritten)
            remaining -= bytesWritten
            logger.d { "write chunk completed: fd=$fd, bytesWritten=$bytesWritten, remaining=$remaining, expected=${remaining >= 0L}" }
            logger.v { "write loop exit: fd=$fd, remaining=$remaining" }
        }
        logger.i { "wrote pipe data: fd=$fd, byteCount=$byteCount" }
        logger.v { "exit write successfully: fd=$fd, byteCount=$byteCount" }
    }

    override fun flush() {
        logger.v { "enter flush: fd=$fd, closed=$closed" }
        if (closed) {
            logger.e { "flush failed; sink is closed: fd=$fd" }
            throw brokenPipe("flush", "pipe sink is closed")
        }
        logger.d { "flush no-op completed: fd=$fd, expected=true" }
        logger.v { "exit flush successfully: fd=$fd" }
    }

    override fun timeout(): Timeout {
        logger.v { "enter timeout for sink: fd=$fd" }
        logger.d { "sink timeout resolved: fd=$fd, timeout=NONE" }
        logger.v { "exit timeout for sink: fd=$fd" }
        return Timeout.NONE
    }

    override fun close() {
        logger.v { "enter sink close: fd=$fd, closed=$closed" }
        if (closed) {
            logger.v { "sink close skipped; already closed: fd=$fd" }
            logger.v { "exit sink close: fd=$fd" }
            return
        }
        closed = true
        if (close(descriptor) != 0) {
            val error = errno
            if (error == EBADF) {
                logger.w { "sink close saw bad descriptor; treating as broken pipe: fd=$fd, errno=$error" }
                throw brokenPipe("close", errnoMessage(error))
            }
            logger.e { "sink close failed: fd=$fd, errno=$error" }
            throw errnoIOException("close", error)
        }
        logger.i { "closed pipe sink: fd=$fd" }
        logger.v { "exit sink close successfully: fd=$fd" }
    }

    private fun markClosed() {
        logger.v { "enter sink markClosed: fd=$fd, closed=$closed" }
        if (closed) {
            logger.v { "sink markClosed skipped; already closed: fd=$fd" }
            logger.v { "exit sink markClosed: fd=$fd" }
            return
        }
        closed = true
        close(descriptor)
        logger.i { "marked pipe sink closed: fd=$fd" }
        logger.v { "exit sink markClosed: fd=$fd" }
    }
}

private fun brokenPipe(operation: String, detail: String): BrokenPipeException =
    BrokenPipeException("$operation failed: $detail", null).also {
        logger.w { "$operation mapped to BrokenPipeException: detail=$detail" }
    }

private fun errnoIOException(operation: String, error: Int): IOException =
    IOException("$operation failed: ${errnoMessage(error)}").also {
        logger.e { "$operation mapped to IOException: errno=$error" }
    }

private fun errnoMessage(error: Int): String {
    logger.v { "enter errnoMessage: errno=$error" }
    val message = strerror(error)?.toKString() ?: "errno $error"
    logger.d { "resolved errno message: errno=$error, message=$message" }
    logger.v { "exit errnoMessage: errno=$error" }
    return message
}
