package top.kagg886.milky.console.util.watcher

import kotlinx.coroutines.flow.Flow
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import top.kagg886.milky.console.util.logger.asTaggedLogger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/21 09:46
 * ================================================
 */

private val logger = "FileWatcher".asTaggedLogger

fun FileSystem.watchFileChange(file: Path, duration: Duration = 50.milliseconds): Flow<FileChange> {
    logger.v { "enter watchFileChange: file=$file, duration=$duration, fileSystem=${this::class.simpleName}" }
    var system = this
    while (system is ForwardingFileSystem) {
        logger.v { "unwrap forwarding file system: current=${system::class.simpleName}" }
        system = system.delegate
    }
    logger.d { "resolved backing file system: system=${system::class.simpleName}, expectedSystem=${system === FileSystem.SYSTEM}" }
    check(system === FileSystem.SYSTEM) {
        logger.e { "watchFileChange rejected unsupported file system: file=$file, system=${system::class.simpleName}" }
        "File watching supports only FileSystem.SYSTEM or ForwardingFileSystem instances that delegate to it"
    }

    val canonicalFile = system.canonicalize(file)
    logger.d { "canonicalized watch path: requested=$file, canonical=$canonicalFile, expected=true" }
    val metadata = system.metadata(canonicalFile)
    logger.d { "loaded watch path metadata: canonical=$canonicalFile, isRegularFile=${metadata.isRegularFile}" }
    require(metadata.isRegularFile) {
        logger.e { "watchFileChange rejected non-regular file: requested=$file, canonical=$canonicalFile" }
        "Only regular files can be watched: $file"
    }

    logger.i { "starting file watch flow: file=$canonicalFile, duration=$duration" }
    logger.v { "exit watchFileChange successfully: file=$canonicalFile" }
    return watchFileChange0(file, duration)
}

internal expect fun watchFileChange0(file: Path, duration: Duration): Flow<FileChange>

enum class FileChange {
    DELETED, MODIFIED
}
