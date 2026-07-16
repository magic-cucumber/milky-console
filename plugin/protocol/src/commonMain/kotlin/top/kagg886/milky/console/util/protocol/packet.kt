package top.kagg886.milky.console.util.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import okio.Buffer
import top.kagg886.milky.console.protocol.MilkyConsoleFromEvent
import top.kagg886.milky.console.util.toBuffer
import kotlin.uuid.Uuid

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/14 11:28
 * ================================================
 */

data class Packet(
    val index: Int? = null,
    val size: Int? = null,
    val uuid: Uuid = Uuid.random(),
    val group: Uuid? = null,
    val data: Buffer = Buffer()
)


@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> T.toPacket(): List<Packet> = Packet(data = this.toBuffer()).split()

@OptIn(ExperimentalSerializationApi::class)
fun MilkyConsoleFromEvent.FromHost.toPacket(): List<Packet> = Packet(data = this.toBuffer()).split()

@OptIn(ExperimentalSerializationApi::class)
fun MilkyConsoleFromEvent.FromPlugin.toPacket(): List<Packet> = Packet(data = this.toBuffer()).split()
