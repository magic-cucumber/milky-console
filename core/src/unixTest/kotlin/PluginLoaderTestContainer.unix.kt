package top.kagg886.milky.console.plugin

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
internal actual fun pluginLoaderTestContainer(): String =
    getenv("MILKY_PLUGIN_TEST_DIRECTORY")?.toKString()
        ?: error("MILKY_PLUGIN_TEST_DIRECTORY was not provided by the Gradle test task")

@OptIn(ExperimentalForeignApi::class)
internal actual fun gradleTestLoggerLevel(): String? =
    getenv("MILKY_GRADLE_TEST_LOGGER_LEVEL")?.toKString()
