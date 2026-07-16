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
    data class Rejected(val message: String) : PluginHandshakeResult
}
