package top.kagg886.milky.console.util.process

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
internal actual fun nativeTestExecutablePath(): String =
    getenv("PROCESS_TEST_EXECUTABLE_PATH")?.toKString()
        ?: error("PROCESS_TEST_EXECUTABLE_PATH was not provided by the Gradle test task")
