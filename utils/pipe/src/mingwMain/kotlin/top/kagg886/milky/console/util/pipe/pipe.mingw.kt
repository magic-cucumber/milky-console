@file:OptIn(ExperimentalForeignApi::class)

package top.kagg886.milky.console.util.pipe


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

actual fun IPCAnonymousPipe.Companion.create(): IPCAnonymousPipe = memScoped {
    val readHandle = alloc<COpaquePointerVar>()
    val writeHandle = alloc<COpaquePointerVar>()
    if (CreatePipe(readHandle.ptr, writeHandle.ptr, null, 0u) == 0) {
        val error = GetLastError()
        throw windowsIOException("CreatePipe", error)
    }

    WindowsIPCPipe(
        sendFD = writeHandle.value.toLong().toULong(),
        receiveFD = readHandle.value.toLong().toULong(),
    )
}

data class WindowsIPCPipe(val sendFD: ULong, val receiveFD: ULong) : IPCAnonymousPipe {
    override val sink: IPCAnonymousPipeSink = IPCAnonymousPipe.fromSink(sendFD)
    override val source: IPCAnonymousPipeSource = IPCAnonymousPipe.fromSource(receiveFD)
}

actual fun IPCAnonymousPipe.Companion.fromSource(fd: ULong): IPCAnonymousPipeSource = WindowsPipeSource(fd)

actual fun IPCAnonymousPipe.Companion.fromSink(fd: ULong): IPCAnonymousPipeSink = WindowsPipeSink(fd)

private abstract class WindowsPipeEnd(
    final override val fd: ULong,
) : HasFileDescriptor {
    protected val handle: HANDLE = fd.toLong().toCPointer<ByteVar>()
        ?: error("null pipe handle")

    final override var closed: Boolean = false
        protected set

    protected fun markClosed() {
        if (closed) return
        closed = true
        CloseHandle(handle)
    }

    protected fun closeHandle() {
        if (closed) return
        closed = true
        if (CloseHandle(handle) == 0) {
            val error = GetLastError()
            if (error.toInt() == ERROR_INVALID_HANDLE) {
                throw windowsBrokenPipe("CloseHandle", error)
            }
            throw windowsIOException("CloseHandle", error)
        }
    }
}

private class WindowsPipeSource(fd: ULong) : WindowsPipeEnd(fd), IPCAnonymousPipeSource {
    private val unsafeCursor = Buffer.UnsafeCursor()

    override fun read(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
        if (closed) throw BrokenPipeException("ReadFile failed: pipe source is closed", null)
        if (byteCount == 0L) return 0L

        val initialSize = sink.size
        val cursor = sink.readAndWriteUnsafe(unsafeCursor)
        try {
            val addedCapacity = cursor.expandBuffer(minOf(byteCount, 1024L).toInt())
            val attemptCount = minOf(byteCount, addedCapacity).toUInt()
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
                        markClosed()
                        return -1
                    }
                    throw windowsIOException("ReadFile", error)
                }
                resultCount.value.toLong()
            }

            cursor.resizeBuffer(initialSize + bytesRead)
            if (bytesRead == 0L) {
                markClosed()
                return -1
            }
            return bytesRead
        } finally {
            cursor.close()
        }
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() = closeHandle()
}

private class WindowsPipeSink(fd: ULong) : WindowsPipeEnd(fd), IPCAnonymousPipeSink {
    private val unsafeCursor = Buffer.UnsafeCursor()

    override fun write(source: Buffer, byteCount: Long) {
        require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
        require(source.size >= byteCount) { "source.size=${source.size} < byteCount=$byteCount" }
        if (closed) throw BrokenPipeException("WriteFile failed: pipe sink is closed", null)

        var remaining = byteCount
        while (remaining > 0L) {
            val cursor = source.readUnsafe(unsafeCursor)
            val bytesWritten: Long
            try {
                val readableCount = cursor.next()
                val attemptCount = minOf(remaining, readableCount.toLong()).toUInt()
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
                            markClosed()
                            throw windowsBrokenPipe("WriteFile", error)
                        }
                        throw windowsIOException("WriteFile", error)
                    }
                    resultCount.value.toLong()
                }
            } finally {
                cursor.close()
            }

            if (bytesWritten == 0L) {
                markClosed()
                throw BrokenPipeException("WriteFile failed: wrote 0 bytes", null)
            }
            source.skip(bytesWritten)
            remaining -= bytesWritten
        }
    }

    override fun flush() {
        if (closed) throw BrokenPipeException("flush failed: pipe sink is closed", null)
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() = closeHandle()
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
    BrokenPipeException("$operation failed: Windows error $error", null)

private fun windowsIOException(operation: String, error: UInt): IOException =
    IOException("$operation failed: Windows error $error")
