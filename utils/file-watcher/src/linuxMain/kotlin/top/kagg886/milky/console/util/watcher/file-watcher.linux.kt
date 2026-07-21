@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package top.kagg886.milky.console.util.watcher

import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okio.Closeable
import okio.Path
import platform.linux.*
import platform.posix.*
import top.kagg886.milky.console.util.logger.asTaggedLogger
import kotlin.time.Duration

private val logger = "LinuxFileWatcher".asTaggedLogger

internal actual fun watchFileChange0(file: Path, duration: Duration): Flow<FileChange> = flow {
    logger.v { "enter watchFileChange0: file=$file, duration=$duration" }
    val watcher = LinuxFileWatcher(file, duration)
    logger.d { "created linux watcher: file=$file, duration=$duration, expected=true" }
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
        logger.v { "closing linux watcher from flow finally: file=$file" }
        watcher.close()
        logger.i { "stopped linux file watcher: file=$file" }
        logger.v { "exit watchFileChange0: file=$file" }
    }
}.flowOn(Dispatchers.Default)

private class LinuxFileWatcher(file: Path, private val duration: Duration) : Closeable {
    private val fd = inotify_init1(IN_CLOEXEC)

    init {
        logger.v { "enter LinuxFileWatcher.init: file=$file, fd=$fd, duration=$duration" }
        check(fd >= 0) {
            logger.e { "inotify_init1 failed; watcher cannot start: file=$file, fd=$fd" }
            "inotify_init1 failed"
        }
        val mask = (IN_MODIFY or IN_ATTRIB or IN_DELETE_SELF or IN_MOVE_SELF).toUInt()
        logger.d { "prepared inotify mask: file=$file, mask=$mask, expected=true" }
        check(inotify_add_watch(fd, file.toString(), mask) >= 0) {
            logger.e { "inotify_add_watch failed; closing fd: file=$file, fd=$fd" }
            close(fd)
            "inotify_add_watch failed for $file"
        }
        logger.i { "started linux file watcher: file=$file, fd=$fd" }
        logger.v { "exit LinuxFileWatcher.init successfully: file=$file, fd=$fd" }
    }

    fun pollChanges(): List<FileChange> = memScoped {
        logger.v { "enter pollChanges: fd=$fd, duration=$duration" }
        val pollFd = alloc<pollfd>()
        pollFd.fd = fd
        pollFd.events = POLLIN.toShort()
        val timeout = duration.inWholeMilliseconds.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        val pollResult = poll(pollFd.ptr, 1u, timeout)
        logger.d { "poll completed: fd=$fd, timeoutMs=$timeout, result=$pollResult, hasEvent=${pollResult > 0}" }
        if (pollResult <= 0) {
            logger.v { "exit pollChanges without events: fd=$fd, pollResult=$pollResult" }
            return@memScoped emptyList()
        }

        val buffer = allocArray<ByteVar>(4096)
        val byteCount = read(fd, buffer, 4096u)
        logger.d { "read inotify buffer: fd=$fd, byteCount=$byteCount, expected=${byteCount > 0}" }
        if (byteCount <= 0) {
            logger.w { "inotify poll reported data but read returned no bytes: fd=$fd, byteCount=$byteCount" }
            logger.v { "exit pollChanges without readable data: fd=$fd" }
            return@memScoped emptyList()
        }

        val changes = buildList {
            var offset = 0
            while (offset < byteCount.toInt()) {
                logger.v { "parse inotify event enter: fd=$fd, offset=$offset, byteCount=$byteCount" }
                val event = (buffer + offset)!!.reinterpret<inotify_event>().pointed
                when {
                    event.mask and (IN_DELETE_SELF.toUInt() or IN_MOVE_SELF.toUInt()) != 0u -> {
                        logger.i { "parsed linux deletion event: fd=$fd, mask=${event.mask}" }
                        add(FileChange.DELETED)
                    }
                    event.mask and (IN_MODIFY.toUInt() or IN_ATTRIB.toUInt()) != 0u -> {
                        logger.i { "parsed linux modification event: fd=$fd, mask=${event.mask}" }
                        add(FileChange.MODIFIED)
                    }
                    else -> logger.v { "ignored linux event mask: fd=$fd, mask=${event.mask}" }
                }
                offset += sizeOf<inotify_event>().toInt() + event.len.toInt()
                logger.v { "parse inotify event exit: fd=$fd, nextOffset=$offset" }
            }
        }
        // unlink can enqueue IN_ATTRIB before IN_DELETE_SELF. Deletion is terminal
        // on every supported platform, so do not expose that incidental modification.
        val result = if (FileChange.DELETED in changes) listOf(FileChange.DELETED) else changes
        logger.d { "normalized linux file events: fd=$fd, raw=$changes, result=$result, expected=true" }
        logger.v { "exit pollChanges: fd=$fd, count=${result.size}" }
        result
    }

    override fun close() {
        logger.v { "enter close: fd=$fd" }
        close(fd)
        logger.i { "closed linux file watcher: fd=$fd" }
        logger.v { "exit close: fd=$fd" }
    }
}
