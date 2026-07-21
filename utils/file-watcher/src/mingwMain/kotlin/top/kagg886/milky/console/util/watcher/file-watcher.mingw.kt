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
import top.kagg886.milky.console.util.logger.asTaggedLogger
import kotlin.time.Duration

private val logger = "WindowsFileWatcher".asTaggedLogger

internal actual fun watchFileChange0(file: Path, duration: Duration): Flow<FileChange> = flow {
    logger.v { "enter watchFileChange0: file=$file, duration=$duration" }
    val watcher = WindowsFileWatcher(file, duration)
    logger.d { "created windows watcher: file=$file, duration=$duration, expected=true" }
    try {
        while (true) {
            logger.v { "poll loop enter: file=$file" }
            currentCoroutineContext().ensureActive()
            logger.v { "coroutine active before poll: file=$file" }
            for (change in watcher.pollChanges()) {
                logger.i { "file event received: file=$file, change=$change" }
                emit(change)
                logger.d { "emitted file event: file=$file, change=$change, expected=true" }
                if (change == FileChange.DELETED) return@flow
            }
            logger.v { "poll loop exit: file=$file" }
        }
    } finally {
        logger.v { "closing windows watcher from flow finally: file=$file" }
        watcher.close()
        logger.i { "stopped windows file watcher: file=$file" }
        logger.v { "exit watchFileChange0: file=$file" }
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
        logger.v { "enter WindowsFileWatcher.init: file=$file, duration=$duration" }
        val parent = requireNotNull(file.parent) { "The watched file must have a parent directory" }
        logger.d { "resolved watched file parent: file=$file, parent=$parent, expected=true" }
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
            logger.e { "CreateFileW failed: parent=$parent, error=${GetLastError()}" }
            "CreateFileW failed for $parent: ${GetLastError()}"
        }
        logger.d { "opened directory handle: parent=$parent, handle=$directory, expected=true" }
        completionEvent = requireNotNull(CreateEventW(null, FALSE, FALSE, null))
        check(completionEvent != INVALID_HANDLE_VALUE) {
            logger.e { "CreateEventW failed; closing directory: parent=$parent, error=${GetLastError()}" }
            CloseHandle(directory)
            "CreateEventW failed: ${GetLastError()}"
        }
        overlapped.hEvent = completionEvent
        logger.i { "started windows file watcher: file=$file, parent=$parent, directory=$directory, event=$completionEvent" }
        logger.v { "exit WindowsFileWatcher.init successfully: file=$file" }
    }

    fun pollChanges(): List<FileChange> = memScoped {
        logger.v { "enter pollChanges: fileName=$fileName, pending=$pending, duration=$duration" }
        if (!pending) {
            logger.v { "pollChanges entering startRead: fileName=$fileName" }
            startRead()
        } else {
            logger.v { "pollChanges reusing pending read: fileName=$fileName" }
        }
        val timeout = duration.inWholeMilliseconds.coerceIn(0, UInt.MAX_VALUE.toLong()).toUInt()
        when (val waitResult = WaitForSingleObject(completionEvent, timeout)) {
            WAIT_TIMEOUT.toUInt() -> {
                logger.d { "wait timed out without file events: fileName=$fileName, timeoutMs=$timeout, expected=true" }
                logger.v { "exit pollChanges without events: fileName=$fileName" }
                return@memScoped emptyList()
            }
            WAIT_OBJECT_0 -> logger.d { "wait completed with file events: fileName=$fileName, waitResult=$waitResult, expected=true" }
            else -> {
                logger.e { "WaitForSingleObject failed: fileName=$fileName, waitResult=$waitResult, error=${GetLastError()}" }
                error("WaitForSingleObject failed: ${GetLastError()}")
            }
        }

        val byteCount = alloc<DWORDVar>()
        check(GetOverlappedResult(directory, overlapped.ptr, byteCount.ptr, FALSE) != 0) {
            logger.e { "GetOverlappedResult failed: fileName=$fileName, error=${GetLastError()}" }
            "GetOverlappedResult failed: ${GetLastError()}"
        }
        pending = false
        logger.d { "overlapped result completed: fileName=$fileName, byteCount=${byteCount.value}, pending=$pending" }
        val result = parseChanges(byteCount.value.toInt())
        logger.v { "exit pollChanges: fileName=$fileName, count=${result.size}" }
        result
    }

    private fun startRead() {
        logger.v { "enter startRead: fileName=$fileName, pending=$pending" }
        check(ResetEvent(completionEvent) != 0) { "ResetEvent failed: ${GetLastError()}" }
        logger.d { "reset completion event: fileName=$fileName, event=$completionEvent, expected=true" }
        val filter = FILE_NOTIFY_CHANGE_FILE_NAME or
            FILE_NOTIFY_CHANGE_LAST_WRITE or
            FILE_NOTIFY_CHANGE_SIZE or
            FILE_NOTIFY_CHANGE_ATTRIBUTES
        logger.d { "prepared directory change filter: fileName=$fileName, filter=$filter, expected=true" }
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
            logger.e { "ReadDirectoryChangesW failed: fileName=$fileName, started=$started, error=${GetLastError()}" }
            "ReadDirectoryChangesW failed: ${GetLastError()}"
        }
        pending = true
        logger.i { "started windows directory read: fileName=$fileName, pending=$pending" }
        logger.v { "exit startRead successfully: fileName=$fileName" }
    }

    private fun parseChanges(byteCount: Int): List<FileChange> = buildList {
        logger.v { "enter parseChanges: fileName=$fileName, byteCount=$byteCount" }
        var offset = 0
        while (offset < byteCount) {
            logger.v { "parse notification enter: fileName=$fileName, offset=$offset, byteCount=$byteCount" }
            val info = pinnedBuffer.addressOf(offset).reinterpret<FILE_NOTIFY_INFORMATION>()
            val nameLength = file_notify_name_length(info).toInt() / 2
            val name = buildString(nameLength) {
                repeat(nameLength) { index ->
                    append(file_notify_name_code_unit(info, index.toUInt()).toInt().toChar())
                }
            }
            if (name == fileName) {
                logger.v { "notification matched watched file: fileName=$fileName, action=${file_notify_action(info)}" }
                when (file_notify_action(info)) {
                    FILE_ACTION_REMOVED.toUInt(), FILE_ACTION_RENAMED_OLD_NAME.toUInt() -> {
                        logger.i { "parsed windows deletion event: fileName=$fileName, action=${file_notify_action(info)}" }
                        add(FileChange.DELETED)
                    }
                    FILE_ACTION_MODIFIED.toUInt(), FILE_ACTION_ADDED.toUInt(), FILE_ACTION_RENAMED_NEW_NAME.toUInt() -> {
                        logger.i { "parsed windows modification event: fileName=$fileName, action=${file_notify_action(info)}" }
                        add(FileChange.MODIFIED)
                    }
                    else -> logger.v { "ignored matched windows action: fileName=$fileName, action=${file_notify_action(info)}" }
                }
            } else {
                logger.v { "ignored notification for another file: watched=$fileName, actual=$name" }
            }
            val next = file_notify_next_offset(info).toInt()
            if (next == 0) {
                logger.v { "parse notification exit at final record: fileName=$fileName, offset=$offset" }
                break
            }
            offset += next
            logger.v { "parse notification exit: fileName=$fileName, nextOffset=$offset" }
        }
        logger.d { "parsed windows file events: fileName=$fileName, byteCount=$byteCount, count=$size, expected=true" }
        logger.v { "exit parseChanges: fileName=$fileName, count=$size" }
    }

    fun close() {
        logger.v { "enter close: fileName=$fileName, closed=$closed, pending=$pending" }
        if (closed) {
            logger.v { "close skipped; already closed: fileName=$fileName" }
            logger.v { "exit close: fileName=$fileName" }
            return
        }
        closed = true
        if (pending) {
            logger.v { "close entering pending cancellation: fileName=$fileName" }
            val cancelled = CancelIoEx(directory, overlapped.ptr)
            logger.d { "cancelled pending directory read: fileName=$fileName, result=$cancelled, expected=true" }
        } else {
            logger.v { "close skipped cancellation; no pending read: fileName=$fileName" }
        }
        CloseHandle(completionEvent)
        CloseHandle(directory)
        pinnedBuffer.unpin()
        nativeHeap.free(overlapped.rawPtr)
        logger.i { "closed windows file watcher: fileName=$fileName" }
        logger.v { "exit close successfully: fileName=$fileName" }
    }

    private companion object {
        const val BUFFER_SIZE = 16 * 1024
    }
}
