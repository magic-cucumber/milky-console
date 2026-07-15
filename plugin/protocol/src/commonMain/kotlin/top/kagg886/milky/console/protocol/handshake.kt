package top.kagg886.milky.console.protocol

import kotlinx.serialization.Serializable

/**
 * ================================================
 * Author:     886kagg
 * Created on: 2026/7/15 21:47
 * ================================================
 */

@Serializable
data object ClientHandshakeRequest

@Serializable
sealed interface ClientHandshakeResult {

    data object Success : ClientHandshakeResult
    data class Failed(val message: String) : ClientHandshakeResult
}
