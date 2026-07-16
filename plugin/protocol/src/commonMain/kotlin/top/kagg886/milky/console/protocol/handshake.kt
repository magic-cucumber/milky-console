package top.kagg886.milky.console.protocol

import kotlinx.serialization.Serializable

/**
 * ================================================
 * Author:     886kagg
 * Created on: 2026/7/15 21:47
 * ================================================
 */

@Serializable
data object ClientHandshakeRequest : MilkyConsoleEvent

@Serializable
sealed interface ClientHandshakeResult : MilkyConsoleEvent {

    data object Success : ClientHandshakeResult
    data class Failed(val message: String) : ClientHandshakeResult
}
@Serializable
data class ClientClosed(val message: String,val stacktrace: String? = null): MilkyConsoleEvent
