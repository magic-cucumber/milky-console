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
data class HandShakePacket(val protocolVersion: Int)

@Serializable
@SerialName("handshake-request-response")
data class HandShakePacketResponsePacket(val allow: Boolean)
