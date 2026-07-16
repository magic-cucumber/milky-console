package top.kagg886.milky.console.protocol

import kotlinx.serialization.Serializable

@Serializable
/* plugin is ready to handshake */
data object PluginHandshakeRequest : MilkyConsoleFromEvent.FromPlugin

/** Host starts a handshake after the loader process and both pipes are ready. */
@Serializable
data object HostHandshakeRequest : MilkyConsoleFromEvent.FromHost

/** Loader reports the only terminal result of a handshake attempt. */
@Serializable
sealed interface PluginHandshakeResult : MilkyConsoleFromEvent.FromPlugin {
    @Serializable
    data object Ready : PluginHandshakeResult

    @Serializable
    data class Rejected(
        val message: String,
        val error: PluginHandshakeError? = null,
    ) : PluginHandshakeResult
}

@Serializable
enum class PluginHandshakeError {
    PROCESS_START_FAILED,
    PROCESS_EXITED,
    TIMEOUT,
    DYNAMIC_LIBRARY_LOAD_FAILED,
    ENTRY_POINT_NOT_FOUND,
    NULL_PLUGIN_API,
    ABI_MISMATCH,
    API_MISMATCH,
    MISSING_ON_LOAD,
    INITIALIZATION_FAILED,
}
