@file:OptIn(ExperimentalForeignApi::class)

package top.kagg886.milky.console.util.watcher

import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okio.Path
import platform.windows.*
import top.kagg886.milky.console.util.watcher.wininterop.file_notify_action
import top.kagg886.milky.console.util.watcher.wininterop.file_notify_name_code_unit
import top.kagg886.milky.console.util.watcher.wininterop.file_notify_name_length
import top.kagg886.milky.console.util.watcher.wininterop.file_notify_next_offset
import kotlin.time.Duration

internal actual fun watchFileChange0(file: Path, duration: Duration): Flow<FileChange> = flow {
    val watcher = WindowsFileWatcher(file, duration)
    try {
        while (true) {
            currentCoroutineContext().ensureActive()
            for (change in watcher.pollChanges()) {
                emit(change)
                if (change == FileChange.DELETED) return@flow
            }
        }
    } finally {
        watcher.close()
    }
}.flowOn(Dispatchers.Default)

private class WindowsFileWatcher(file: Path, private val duration: Duration) {
    private val fileName = file.name
    private val directory: HANDLE
    private val completionEvent: HANDLE
    private val overlapped = nativeHeap.alloc<OVERLAPPED>()
    private val buffer = ByteArray(BUFFER_SIZE)
    private val pinnedBuffer = buffer.pin()
    private var pending = false
    private var closed = false

    init {
        val parent = requireNotNull(file.parent) { "The watched file must have a parent directory" }
        directory = requireNotNull(CreateFileW(
            parent.toString(),
            FILE_LIST_DIRECTORY.toUInt(),
            (FILE_SHARE_READ or FILE_SHARE_WRITE or FILE_SHARE_DELETE).toUInt(),
            null,
            OPEN_EXISTING.toUInt(),
            (FILE_FLAG_BACKUP_SEMANTICS or FILE_FLAG_OVERLAPPED).toUInt(),
            null,
        ))
        check(directory != INVALID_HANDLE_VALUE) {
            "CreateFileW failed for $parent: ${GetLastError()}"
        }
        completionEvent = requireNotNull(CreateEventW(null, FALSE, FALSE, null))
        check(completionEvent != INVALID_HANDLE_VALUE) {
            CloseHandle(directory)
            "CreateEventW failed: ${GetLastError()}"
        }
        overlapped.hEvent = completionEvent
    }

    fun pollChanges(): List<FileChange> = memScoped {
        if (!pending) startRead()
        when (WaitForSingleObject(completionEvent, duration.inWholeMilliseconds.coerceIn(0, UInt.MAX_VALUE.toLong()).toUInt())) {
            WAIT_TIMEOUT.toUInt() -> return@memScoped emptyList()
            WAIT_OBJECT_0 -> Unit
            else -> error("WaitForSingleObject failed: ${GetLastError()}")
        }

        val byteCount = alloc<DWORDVar>()
        check(GetOverlappedResult(directory, overlapped.ptr, byteCount.ptr, FALSE) != 0) {
            "GetOverlappedResult failed: ${GetLastError()}"
        }
        pending = false
        parseChanges(byteCount.value.toInt())
    }

    private fun startRead() {
        check(ResetEvent(completionEvent) != 0) { "ResetEvent failed: ${GetLastError()}" }
        val filter = FILE_NOTIFY_CHANGE_FILE_NAME or
            FILE_NOTIFY_CHANGE_LAST_WRITE or
            FILE_NOTIFY_CHANGE_SIZE or
            FILE_NOTIFY_CHANGE_ATTRIBUTES
        val started = ReadDirectoryChangesW(
            directory,
            pinnedBuffer.addressOf(0),
            BUFFER_SIZE.toUInt(),
            FALSE,
            filter.toUInt(),
            null,
            overlapped.ptr,
            null,
        )
        check(started != 0 || GetLastError() == ERROR_IO_PENDING.toUInt()) {
            "ReadDirectoryChangesW failed: ${GetLastError()}"
        }
        pending = true
    }

    private fun parseChanges(byteCount: Int): List<FileChange> = buildList {
        var offset = 0
        while (offset < byteCount) {
            val info = pinnedBuffer.addressOf(offset).reinterpret<FILE_NOTIFY_INFORMATION>()
            val nameLength = file_notify_name_length(info).toInt() / 2
            val name = buildString(nameLength) {
                repeat(nameLength) { index ->
                    append(file_notify_name_code_unit(info, index.toUInt()).toInt().toChar())
                }
            }
            if (name == fileName) {
                when (file_notify_action(info)) {
                    FILE_ACTION_REMOVED.toUInt(), FILE_ACTION_RENAMED_OLD_NAME.toUInt() -> add(FileChange.DELETED)
                    FILE_ACTION_MODIFIED.toUInt(), FILE_ACTION_ADDED.toUInt(), FILE_ACTION_RENAMED_NEW_NAME.toUInt() -> add(FileChange.MODIFIED)
                }
            }
            val next = file_notify_next_offset(info).toInt()
            if (next == 0) break
            offset += next
        }
    }

    fun close() {
        if (closed) return
        closed = true
        if (pending) CancelIoEx(directory, overlapped.ptr)
        CloseHandle(completionEvent)
        CloseHandle(directory)
        pinnedBuffer.unpin()
        nativeHeap.free(overlapped.rawPtr)
    }

    private companion object {
        const val BUFFER_SIZE = 16 * 1024
    }
}
