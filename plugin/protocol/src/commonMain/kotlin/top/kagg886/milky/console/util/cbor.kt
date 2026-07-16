package top.kagg886.milky.console.util

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import okio.Buffer
import top.kagg886.milky.console.protocol.ClientClosed
import top.kagg886.milky.console.protocol.ClientHandshakeRequest
import top.kagg886.milky.console.protocol.ClientHandshakeResult
import top.kagg886.milky.console.protocol.MilkyConsoleEvent
import top.kagg886.milky.console.protocol.ProtocolEvent

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/14 13:31
 * ================================================
 */

@OptIn(ExperimentalSerializationApi::class)
val MilkyConsoleCbor = Cbor {
    serializersModule = SerializersModule {
        polymorphic(MilkyConsoleEvent::class) {
            subclass(ClientHandshakeRequest::class)
            subclass(ClientHandshakeResult.Success::class)
            subclass(ClientHandshakeResult.Failed::class)
            subclass(ProtocolEvent::class)
            subclass(ClientClosed::class)
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> Buffer.readContent(format: Cbor = MilkyConsoleCbor): T = format.decodeFromByteArray(readByteArray())

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> T.toBuffer(format: Cbor = MilkyConsoleCbor) : Buffer = Buffer().apply {
    write(format.encodeToByteArray(this@toBuffer))
}
