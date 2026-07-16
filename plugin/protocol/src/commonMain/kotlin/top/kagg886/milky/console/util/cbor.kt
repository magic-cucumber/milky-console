package top.kagg886.milky.console.util

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import okio.Buffer
import top.kagg886.milky.console.protocol.HostClose
import top.kagg886.milky.console.protocol.HostEvent
import top.kagg886.milky.console.protocol.HostHandshakeRequest
import top.kagg886.milky.console.protocol.MilkyConsoleFromEvent
import top.kagg886.milky.console.protocol.PluginClosed
import top.kagg886.milky.console.protocol.PluginEvent
import top.kagg886.milky.console.protocol.PluginHandshakeResult

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/14 13:31
 * ================================================
 */

@OptIn(ExperimentalSerializationApi::class)
val MilkyConsoleCbor = Cbor {
    serializersModule = SerializersModule {
        polymorphic(MilkyConsoleFromEvent.FromHost::class) {
            subclass(HostHandshakeRequest::class)
            subclass(HostEvent::class)
            subclass(HostClose::class)
        }
        polymorphic(MilkyConsoleFromEvent.FromPlugin::class) {
            polymorphic(PluginHandshakeResult::class) {
                subclass(PluginHandshakeResult.Ready::class)
                subclass(PluginHandshakeResult.Rejected::class)
            }
            subclass(PluginEvent::class)
            subclass(PluginClosed::class)
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> Buffer.readContent(format: Cbor = MilkyConsoleCbor): T = format.decodeFromByteArray(readByteArray())

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> T.toBuffer(format: Cbor = MilkyConsoleCbor) : Buffer = Buffer().apply {
    write(format.encodeToByteArray(this@toBuffer))
}

@OptIn(ExperimentalSerializationApi::class)
fun MilkyConsoleFromEvent.FromHost.toBuffer(format: Cbor = MilkyConsoleCbor): Buffer = Buffer().apply {
    write(format.encodeToByteArray(PolymorphicSerializer(MilkyConsoleFromEvent.FromHost::class), this@toBuffer))
}

@OptIn(ExperimentalSerializationApi::class)
fun MilkyConsoleFromEvent.FromPlugin.toBuffer(format: Cbor = MilkyConsoleCbor): Buffer = Buffer().apply {
    write(format.encodeToByteArray(PolymorphicSerializer(MilkyConsoleFromEvent.FromPlugin::class), this@toBuffer))
}
