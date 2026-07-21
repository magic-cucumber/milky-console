package top.kagg886.milky.console.util.watcher

import kotlinx.coroutines.flow.Flow
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/21 09:46
 * ================================================
 */

fun FileSystem.watchFileChange(file: Path, duration: Duration = 50.milliseconds): Flow<FileChange> {
    var system = this
    while (system is ForwardingFileSystem) {
        system = system.delegate
    }
    check(system === FileSystem.SYSTEM) {
        "File watching supports only FileSystem.SYSTEM or ForwardingFileSystem instances that delegate to it"
    }

    val canonicalFile = system.canonicalize(file)
    require(system.metadata(canonicalFile).isRegularFile) {
        "Only regular files can be watched: $file"
    }

    return watchFileChange0(file, duration)
}

internal expect fun watchFileChange0(file: Path, duration: Duration): Flow<FileChange>

enum class FileChange {
    DELETED, MODIFIED
}
