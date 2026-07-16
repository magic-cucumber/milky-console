package top.kagg886.milky.console.util

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import okio.Buffer
import top.kagg886.milky.console.protocol.MilkyConsoleFromEvent

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/14 13:31
 * ================================================
 */


@OptIn(ExperimentalSerializationApi::class)
val MilkyConsoleCbor = Cbor

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> Buffer.readContent(format: Cbor = MilkyConsoleCbor): T =
    format.decodeFromByteArray(readByteArray())

@OptIn(ExperimentalSerializationApi::class)
fun <T> Buffer.readContent(serializer: KSerializer<T>, format: Cbor = MilkyConsoleCbor): T =
    format.decodeFromByteArray(serializer, readByteArray())

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> T.toBuffer(format: Cbor = MilkyConsoleCbor): Buffer = Buffer().apply {
    write(format.encodeToByteArray(this@toBuffer))
}

@OptIn(ExperimentalSerializationApi::class)
fun MilkyConsoleFromEvent.FromHost.toBuffer(format: Cbor = MilkyConsoleCbor): Buffer = Buffer().apply {
    write(format.encodeToByteArray(MilkyConsoleFromEvent.FromHost.serializer(), this@toBuffer))
}

@OptIn(ExperimentalSerializationApi::class)
fun MilkyConsoleFromEvent.FromPlugin.toBuffer(format: Cbor = MilkyConsoleCbor): Buffer = Buffer().apply {
    write(format.encodeToByteArray(MilkyConsoleFromEvent.FromPlugin.serializer(), this@toBuffer))
}
