import top.kagg886.milky.console.util.protocol.Packet

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/16 10:39
 * ================================================
 */

data class SendPacket(
    val packet: Packet
)

data class ReceivePacket(
    val packet: Packet
)
