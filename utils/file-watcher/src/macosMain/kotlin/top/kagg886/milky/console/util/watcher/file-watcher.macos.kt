@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package top.kagg886.milky.console.util.watcher

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okio.Closeable
import okio.Path
import platform.darwin.*
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.open
import platform.posix.timespec
import top.kagg886.milky.console.util.logger.asTaggedLogger
import kotlin.time.Duration

private val logger = "MacosFileWatcher".asTaggedLogger

internal actual fun watchFileChange0(file: Path, duration: Duration): Flow<FileChange> = flow {
    logger.v { "enter watchFileChange0: file=$file, duration=$duration" }
    val watcher = MacosFileWatcher(file, duration)
    logger.d { "created macos watcher: file=$file, duration=$duration, expected=true" }
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
        logger.v { "closing macos watcher from flow finally: file=$file" }
        watcher.close()
        logger.i { "stopped macos file watcher: file=$file" }
        logger.v { "exit watchFileChange0: file=$file" }
    }
}.flowOn(Dispatchers.Default)

private class MacosFileWatcher(file: Path, private val duration: Duration) : Closeable {
    private val fileDescriptor = open(file.toString(), O_RDONLY)
    private val queue = kqueue()

    init {
        logger.v { "enter MacosFileWatcher.init: file=$file, fd=$fileDescriptor, queue=$queue, duration=$duration" }
        check(fileDescriptor >= 0) {
            logger.e { "open failed; watcher cannot start: file=$file, fd=$fileDescriptor" }
            "open failed for $file"
        }
        check(queue >= 0) {
            logger.e { "kqueue failed; closing file descriptor: file=$file, fd=$fileDescriptor" }
            close(fileDescriptor)
            "kqueue failed"
        }
        memScoped {
            logger.v { "enter kevent registration: file=$file, fd=$fileDescriptor, queue=$queue" }
            val change = alloc<kevent>()
            change.ident = fileDescriptor.toULong()
            change.filter = EVFILT_VNODE.toShort()
            change.flags = (EV_ADD or EV_ENABLE or EV_CLEAR).toUShort()
            change.fflags =
                (NOTE_WRITE or NOTE_EXTEND or NOTE_ATTRIB or NOTE_DELETE or NOTE_RENAME or NOTE_REVOKE).toUInt()
            logger.d { "prepared macos vnode event registration: file=$file, fflags=${change.fflags}, expected=true" }
            check(kevent(queue, change.ptr, 1, null, 0, null) == 0) {
                logger.e { "kevent registration failed; closing watcher handles: file=$file, fd=$fileDescriptor, queue=$queue" }
                close(queue)
                close(fileDescriptor)
                "kevent registration failed"
            }
            logger.v { "exit kevent registration successfully: file=$file, queue=$queue" }
        }
        logger.i { "started macos file watcher: file=$file, fd=$fileDescriptor, queue=$queue" }
        logger.v { "exit MacosFileWatcher.init successfully: file=$file" }
    }

    fun pollChanges(): List<FileChange> = memScoped {
        logger.v { "enter pollChanges: fd=$fileDescriptor, queue=$queue, duration=$duration" }
        val event = alloc<kevent>()
        val timeout = alloc<timespec>()
        timeout.tv_sec = duration.inWholeSeconds
        timeout.tv_nsec = duration.inWholeNanoseconds % 1_000_000_000
        val eventCount = kevent(queue, null, 0, event.ptr, 1, timeout.ptr)
        logger.d { "kevent poll completed: queue=$queue, eventCount=$eventCount, timeoutSec=${timeout.tv_sec}, timeoutNsec=${timeout.tv_nsec}" }
        if (eventCount <= 0) {
            logger.v { "exit pollChanges without events: queue=$queue, eventCount=$eventCount" }
            return@memScoped emptyList()
        }
        val result = when {
            event.fflags and (NOTE_DELETE or NOTE_RENAME or NOTE_REVOKE).toUInt() != 0u -> {
                logger.i { "parsed macos deletion event: queue=$queue, fflags=${event.fflags}" }
                listOf(FileChange.DELETED)
            }
            event.fflags and (NOTE_WRITE or NOTE_EXTEND or NOTE_ATTRIB).toUInt() != 0u -> {
                logger.i { "parsed macos modification event: queue=$queue, fflags=${event.fflags}" }
                listOf(FileChange.MODIFIED)
            }
            else -> {
                logger.v { "ignored macos event flags: queue=$queue, fflags=${event.fflags}" }
                emptyList()
            }
        }
        logger.d { "normalized macos file events: queue=$queue, result=$result, expected=true" }
        logger.v { "exit pollChanges: queue=$queue, count=${result.size}" }
        result
    }

    override fun close() {
        logger.v { "enter close: fd=$fileDescriptor, queue=$queue" }
        close(queue)
        close(fileDescriptor)
        logger.i { "closed macos file watcher: fd=$fileDescriptor, queue=$queue" }
        logger.v { "exit close: fd=$fileDescriptor, queue=$queue" }
    }
}
