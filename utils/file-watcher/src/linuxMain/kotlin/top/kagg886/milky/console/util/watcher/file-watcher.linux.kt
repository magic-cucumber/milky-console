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
import kotlin.time.Duration

internal actual fun watchFileChange0(file: Path, duration: Duration): Flow<FileChange> = flow {
    val watcher = LinuxFileWatcher(file, duration)
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

private class LinuxFileWatcher(file: Path, private val duration: Duration) : Closeable {
    private val fd = inotify_init1(IN_CLOEXEC)

    init {
        check(fd >= 0) { "inotify_init1 failed" }
        val mask = (IN_MODIFY or IN_ATTRIB or IN_DELETE_SELF or IN_MOVE_SELF).toUInt()
        check(inotify_add_watch(fd, file.toString(), mask) >= 0) {
            close(fd)
            "inotify_add_watch failed for $file"
        }
    }

    fun pollChanges(): List<FileChange> = memScoped {
        val pollFd = alloc<pollfd>()
        pollFd.fd = fd
        pollFd.events = POLLIN.toShort()
        if (poll(pollFd.ptr, 1u, duration.inWholeMilliseconds.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()) <= 0) return@memScoped emptyList()

        val buffer = allocArray<ByteVar>(4096)
        val byteCount = read(fd, buffer, 4096u)
        if (byteCount <= 0) return@memScoped emptyList()

        buildList {
            var offset = 0
            while (offset < byteCount.toInt()) {
                val event = (buffer + offset)!!.reinterpret<inotify_event>().pointed
                when {
                    event.mask and (IN_DELETE_SELF.toUInt() or IN_MOVE_SELF.toUInt()) != 0u -> add(FileChange.DELETED)
                    event.mask and (IN_MODIFY.toUInt() or IN_ATTRIB.toUInt()) != 0u -> add(FileChange.MODIFIED)
                }
                offset += sizeOf<inotify_event>().toInt() + event.len.toInt()
            }
        }
    }

    override fun close() {
        close(fd)
    }
}
