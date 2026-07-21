@file:OptIn(ExperimentalForeignApi::class)

package top.kagg886.milky.console.util.pipe


import co.touchlab.kermit.Logger
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import okio.Buffer
import okio.IOException
import okio.Timeout
import platform.windows.CloseHandle
import platform.windows.CreatePipe
import platform.windows.ERROR_BROKEN_PIPE
import platform.windows.ERROR_HANDLE_EOF
import platform.windows.ERROR_INVALID_HANDLE
import platform.windows.ERROR_NO_DATA
import platform.windows.ERROR_PIPE_NOT_CONNECTED
import platform.windows.GetLastError
import platform.windows.HANDLE
import platform.windows.ReadFile
import platform.windows.WriteFile

private val logger = Logger.withTag("WindowsPipe")

actual fun IPCAnonymousPipe.Companion.create(): IPCAnonymousPipe = memScoped {
    logger.v { "enter create" }
    val readHandle = alloc<COpaquePointerVar>()
    val writeHandle = alloc<COpaquePointerVar>()
    if (CreatePipe(readHandle.ptr, writeHandle.ptr, null, 0u) == 0) {
        val error = GetLastError()
        logger.e { "CreatePipe failed; pipe not opened: error=$error" }
        throw windowsIOException("CreatePipe", error)
    }

    val pipe = WindowsIPCPipe(
        sendFD = writeHandle.value.toLong().toULong(),
        receiveFD = readHandle.value.toLong().toULong(),
    )
    logger.i { "opened anonymous pipe: sourceFd=${pipe.receiveFD}, sinkFd=${pipe.sendFD}" }
    logger.v { "exit create successfully: sourceFd=${pipe.receiveFD}, sinkFd=${pipe.sendFD}" }
    pipe
}

data class WindowsIPCPipe(val sendFD: ULong, val receiveFD: ULong) : IPCAnonymousPipe {
    override val sink: IPCAnonymousPipeSink = IPCAnonymousPipe.fromSink(sendFD)
    override val source: IPCAnonymousPipeSource = IPCAnonymousPipe.fromSource(receiveFD)
}

actual fun IPCAnonymousPipe.Companion.fromSource(fd: ULong): IPCAnonymousPipeSource {
    logger.v { "enter fromSource: fd=$fd" }
    val source = WindowsPipeSource(fd)
    logger.d { "created pipe source wrapper: fd=$fd, expected=true" }
    logger.v { "exit fromSource: fd=$fd" }
    return source
}

actual fun IPCAnonymousPipe.Companion.fromSink(fd: ULong): IPCAnonymousPipeSink {
    logger.v { "enter fromSink: fd=$fd" }
    val sink = WindowsPipeSink(fd)
    logger.d { "created pipe sink wrapper: fd=$fd, expected=true" }
    logger.v { "exit fromSink: fd=$fd" }
    return sink
}

private abstract class WindowsPipeEnd(
    final override val fd: ULong,
) : HasFileDescriptor {
    protected val handle: HANDLE = fd.toLong().toCPointer<ByteVar>()
        ?: error("null pipe handle")

    final override var closed: Boolean = false
        protected set

    protected fun markClosed() {
        logger.v { "enter markClosed: fd=$fd, closed=$closed" }
        if (closed) {
            logger.v { "markClosed skipped; already closed: fd=$fd" }
            logger.v { "exit markClosed: fd=$fd" }
            return
        }
        closed = true
        CloseHandle(handle)
        logger.i { "marked pipe end closed: fd=$fd" }
        logger.v { "exit markClosed: fd=$fd" }
    }

    protected fun closeHandle() {
        logger.v { "enter closeHandle: fd=$fd, closed=$closed" }
        if (closed) {
            logger.v { "closeHandle skipped; already closed: fd=$fd" }
            logger.v { "exit closeHandle: fd=$fd" }
            return
        }
        closed = true
        if (CloseHandle(handle) == 0) {
            val error = GetLastError()
            if (error.toInt() == ERROR_INVALID_HANDLE) {
                logger.w { "CloseHandle reported invalid handle; treating as broken pipe: fd=$fd, error=$error" }
                throw windowsBrokenPipe("CloseHandle", error)
            }
            logger.e { "CloseHandle failed: fd=$fd, error=$error" }
            throw windowsIOException("CloseHandle", error)
        }
        logger.i { "closed pipe end: fd=$fd" }
        logger.v { "exit closeHandle successfully: fd=$fd" }
    }
}

private class WindowsPipeSource(fd: ULong) : WindowsPipeEnd(fd), IPCAnonymousPipeSource {
    private val unsafeCursor = Buffer.UnsafeCursor()

    override fun read(sink: Buffer, byteCount: Long): Long {
        logger.v { "enter read: fd=$fd, byteCount=$byteCount, closed=$closed, sinkSize=${sink.size}" }
        require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
        if (closed) {
            logger.e { "read failed before IO; source is closed: fd=$fd" }
            throw BrokenPipeException("ReadFile failed: pipe source is closed", null)
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
            val attemptCount = minOf(byteCount, addedCapacity).toUInt()
            logger.d { "prepared read buffer: fd=$fd, initialSize=$initialSize, addedCapacity=$addedCapacity, attemptCount=$attemptCount" }
            val bytesRead = memScoped {
                val resultCount = alloc<UIntVar>()
                val result = cursor.data!!.usePinned { pinned ->
                    ReadFile(
                        hFile = handle,
                        lpBuffer = pinned.addressOf(cursor.start),
                        nNumberOfBytesToRead = attemptCount,
                        lpNumberOfBytesRead = resultCount.ptr,
                        lpOverlapped = null,
                    )
                }
                if (result == 0) {
                    val error = GetLastError()
                    cursor.resizeBuffer(initialSize)
                    if (isBrokenPipeError(error.toInt())) {
                        logger.i { "read reached closed peer: fd=$fd, error=$error" }
                        markClosed()
                        logger.v { "exit read: fd=$fd, eof=true" }
                        return -1
                    }
                    logger.e { "ReadFile failed: fd=$fd, error=$error" }
                    throw windowsIOException("ReadFile", error)
                }
                resultCount.value.toLong()
            }

            cursor.resizeBuffer(initialSize + bytesRead)
            if (bytesRead == 0L) {
                logger.i { "read returned zero bytes; closing source: fd=$fd" }
                markClosed()
                logger.v { "exit read: fd=$fd, eof=true" }
                return -1
            }
            logger.d { "read completed: fd=$fd, bytesRead=$bytesRead, sinkSize=${sink.size}, expected=${bytesRead > 0L}" }
            logger.v { "exit read successfully: fd=$fd, bytesRead=$bytesRead" }
            return bytesRead
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
        logger.v { "enter source close: fd=$fd" }
        closeHandle()
        logger.v { "exit source close: fd=$fd, closed=$closed" }
    }
}

private class WindowsPipeSink(fd: ULong) : WindowsPipeEnd(fd), IPCAnonymousPipeSink {
    private val unsafeCursor = Buffer.UnsafeCursor()

    override fun write(source: Buffer, byteCount: Long) {
        logger.v { "enter write: fd=$fd, byteCount=$byteCount, closed=$closed, sourceSize=${source.size}" }
        require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
        require(source.size >= byteCount) { "source.size=${source.size} < byteCount=$byteCount" }
        if (closed) {
            logger.e { "write failed before IO; sink is closed: fd=$fd" }
            throw BrokenPipeException("WriteFile failed: pipe sink is closed", null)
        }

        var remaining = byteCount
        logger.d { "write loop initialized: fd=$fd, remaining=$remaining, expected=${remaining >= 0L}" }
        while (remaining > 0L) {
            logger.v { "write loop enter: fd=$fd, remaining=$remaining" }
            val cursor = source.readUnsafe(unsafeCursor)
            val bytesWritten: Long
            try {
                val readableCount = cursor.next()
                val attemptCount = minOf(remaining, readableCount.toLong()).toUInt()
                logger.d { "prepared write chunk: fd=$fd, readableCount=$readableCount, attemptCount=$attemptCount" }
                bytesWritten = memScoped {
                    val resultCount = alloc<UIntVar>()
                    val result = cursor.data!!.usePinned { pinned ->
                        WriteFile(
                            hFile = handle,
                            lpBuffer = pinned.addressOf(cursor.start),
                            nNumberOfBytesToWrite = attemptCount,
                            lpNumberOfBytesWritten = resultCount.ptr,
                            lpOverlapped = null,
                        )
                    }
                    if (result == 0) {
                        val error = GetLastError()
                        if (isBrokenPipeError(error.toInt())) {
                            logger.e { "WriteFile hit broken pipe: fd=$fd, error=$error" }
                            markClosed()
                            throw windowsBrokenPipe("WriteFile", error)
                        }
                        logger.e { "WriteFile failed: fd=$fd, error=$error" }
                        throw windowsIOException("WriteFile", error)
                    }
                    resultCount.value.toLong()
                }
            } finally {
                logger.v { "closing write cursor: fd=$fd" }
                cursor.close()
            }

            if (bytesWritten == 0L) {
                logger.e { "WriteFile made no progress; closing sink: fd=$fd" }
                markClosed()
                throw BrokenPipeException("WriteFile failed: wrote 0 bytes", null)
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
            throw BrokenPipeException("flush failed: pipe sink is closed", null)
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
        logger.v { "enter sink close: fd=$fd" }
        closeHandle()
        logger.v { "exit sink close: fd=$fd, closed=$closed" }
    }
}

private fun isBrokenPipeError(error: Int): Boolean = when (error) {
    ERROR_BROKEN_PIPE,
    ERROR_HANDLE_EOF,
    ERROR_INVALID_HANDLE,
    ERROR_NO_DATA,
    ERROR_PIPE_NOT_CONNECTED,
        -> true

    else -> false
}

private fun windowsBrokenPipe(operation: String, error: UInt): BrokenPipeException =
    BrokenPipeException("$operation failed: Windows error $error", null).also {
        logger.w { "$operation mapped to BrokenPipeException: error=$error" }
    }

private fun windowsIOException(operation: String, error: UInt): IOException =
    IOException("$operation failed: Windows error $error").also {
        logger.e { "$operation mapped to IOException: error=$error" }
    }
