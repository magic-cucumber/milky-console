package top.kagg886.milky.console.util.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import okio.Buffer
import co.touchlab.kermit.Logger
import top.kagg886.milky.console.protocol.MilkyConsoleFromEvent
import top.kagg886.milky.console.util.toBuffer
import kotlin.uuid.Uuid

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/14 11:28
 * ================================================
 */

private val logger = Logger.withTag("ProtocolPacket")

data class Packet(
    val index: Int? = null,
    val size: Int? = null,
    val uuid: Uuid = Uuid.random(),
    val group: Uuid? = null,
    val data: Buffer = Buffer()
)


@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> T.toPacket(): List<Packet> {
    val logger = Logger.withTag("ProtocolPacket")
    logger.v { "enter toPacket<${T::class.simpleName}>" }
    val buffer = this.toBuffer()
    logger.d { "generic payload converted to buffer: bytes=${buffer.size}, expectedNonEmpty=${buffer.size > 0L}" }
    val packets = Packet(data = buffer).split()
    logger.i { "generic payload converted to packets: type=${T::class.simpleName}, packets=${packets.size}" }
    logger.v { "exit toPacket<${T::class.simpleName}> successfully" }
    return packets
}

@OptIn(ExperimentalSerializationApi::class)
fun MilkyConsoleFromEvent.FromHost.toPacket(): List<Packet> {
    logger.v { "enter FromHost.toPacket: type=${this::class.simpleName}" }
    val buffer = this.toBuffer()
    logger.d { "host event converted to buffer: type=${this::class.simpleName}, bytes=${buffer.size}, expectedNonEmpty=${buffer.size > 0L}" }
    val packets = Packet(data = buffer).split()
    logger.i { "host event converted to packets: type=${this::class.simpleName}, packets=${packets.size}" }
    logger.v { "exit FromHost.toPacket successfully: type=${this::class.simpleName}" }
    return packets
}

@OptIn(ExperimentalSerializationApi::class)
fun MilkyConsoleFromEvent.FromPlugin.toPacket(): List<Packet> {
    logger.v { "enter FromPlugin.toPacket: type=${this::class.simpleName}" }
    val buffer = this.toBuffer()
    logger.d { "plugin event converted to buffer: type=${this::class.simpleName}, bytes=${buffer.size}, expectedNonEmpty=${buffer.size > 0L}" }
    val packets = Packet(data = buffer).split()
    logger.i { "plugin event converted to packets: type=${this::class.simpleName}, packets=${packets.size}" }
    logger.v { "exit FromPlugin.toPacket successfully: type=${this::class.simpleName}" }
    return packets
}
