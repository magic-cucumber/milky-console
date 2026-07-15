package top.kagg886.milky.console.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/14 13:13
 * ================================================
 */

@Serializable
@SerialName("handshake-request-ready")
data object HandShakeRequestReadyPacket

@Serializable
@SerialName("handshake-request")
data class HandShakePacket(val config: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HandShakePacket) return false

        if (!config.contentEquals(other.config)) return false

        return true
    }

    override fun hashCode(): Int {
        return config.contentHashCode()
    }
}

@Serializable
@SerialName("handshake-request-response")
data class HandShakePacketResponsePacket(val allow: Boolean)
