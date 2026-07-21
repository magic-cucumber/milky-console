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
import kotlin.time.Duration

internal actual fun watchFileChange0(file: Path, duration: Duration): Flow<FileChange> = flow {
    val watcher = MacosFileWatcher(file, duration)
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

private class MacosFileWatcher(file: Path, private val duration: Duration) : Closeable {
    private val fileDescriptor = open(file.toString(), O_RDONLY)
    private val queue = kqueue()

    init {
        check(fileDescriptor >= 0) { "open failed for $file" }
        check(queue >= 0) {
            close(fileDescriptor)
            "kqueue failed"
        }
        memScoped {
            val change = alloc<kevent>()
            change.ident = fileDescriptor.toULong()
            change.filter = EVFILT_VNODE.toShort()
            change.flags = (EV_ADD or EV_ENABLE or EV_CLEAR).toUShort()
            change.fflags =
                (NOTE_WRITE or NOTE_EXTEND or NOTE_ATTRIB or NOTE_DELETE or NOTE_RENAME or NOTE_REVOKE).toUInt()
            check(kevent(queue, change.ptr, 1, null, 0, null) == 0) {
                close(queue)
                close(fileDescriptor)
                "kevent registration failed"
            }
        }
    }

    fun pollChanges(): List<FileChange> = memScoped {
        val event = alloc<kevent>()
        val timeout = alloc<timespec>()
        timeout.tv_sec = duration.inWholeSeconds
        timeout.tv_nsec = duration.inWholeNanoseconds % 1_000_000_000
        if (kevent(queue, null, 0, event.ptr, 1, timeout.ptr) <= 0) return@memScoped emptyList()
        when {
            event.fflags and (NOTE_DELETE or NOTE_RENAME or NOTE_REVOKE).toUInt() != 0u -> listOf(FileChange.DELETED)
            event.fflags and (NOTE_WRITE or NOTE_EXTEND or NOTE_ATTRIB).toUInt() != 0u -> listOf(FileChange.MODIFIED)
            else -> emptyList()
        }
    }

    override fun close() {
        close(queue)
        close(fileDescriptor)
    }
}
