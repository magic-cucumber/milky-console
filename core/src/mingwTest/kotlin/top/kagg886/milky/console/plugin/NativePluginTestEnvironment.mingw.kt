package top.kagg886.milky.console.plugin

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
internal actual fun nativePluginTestEnvironment() = NativePluginTestEnvironment(
    acceptLibrary = requireEnvironment("MILKY_PLUGIN_TEST_ACCEPT_LIBRARY"),
    rejectLibrary = requireEnvironment("MILKY_PLUGIN_TEST_REJECT_LIBRARY"),
    loader = requireEnvironment("MILKY_PLUGIN_TEST_LOADER"),
    messageMarker = requireEnvironment("MILKY_PLUGIN_TEST_MESSAGE_MARKER"),
)

@OptIn(ExperimentalForeignApi::class)
private fun requireEnvironment(name: String): String =
    requireNotNull(getenv(name)?.toKString()) { "Missing test environment variable: $name" }
