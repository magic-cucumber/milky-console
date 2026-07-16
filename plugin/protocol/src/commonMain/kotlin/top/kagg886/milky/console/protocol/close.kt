package top.kagg886.milky.console.protocol

import kotlinx.serialization.Serializable

/** Host asks the loader to stop the plugin and exit. */
@Serializable
data class HostClose(val reason: String) : MilkyConsoleFromEvent.FromHost

/** Loader reports an unexpected plugin-side termination to the host. */
@Serializable
data class PluginClosed(
    val message: String,
    val stacktrace: String? = null,
) : MilkyConsoleFromEvent.FromPlugin
