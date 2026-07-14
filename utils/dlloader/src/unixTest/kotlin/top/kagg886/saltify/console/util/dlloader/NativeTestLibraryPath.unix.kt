package top.kagg886.saltify.console.util.dlloader

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
internal actual fun nativeTestLibraryPath(): String =
    getenv("DLLOADER_TEST_LIBRARY_PATH")?.toKString()
        ?: error("DLLOADER_TEST_LIBRARY_PATH was not provided by the Gradle test task")
