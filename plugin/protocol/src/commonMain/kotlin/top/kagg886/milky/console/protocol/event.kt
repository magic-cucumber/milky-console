package top.kagg886.milky.console.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.ntqqrev.milky.ApiGeneralResponse
import org.ntqqrev.milky.Event
import kotlin.uuid.Uuid

/** A host event delivered to the loaded native plugin. */
@Serializable
data class HostEvent(val event: Event) : MilkyConsoleFromEvent.FromHost

/** A plugin event delivered to the host. */
@Serializable
data class PluginApiRequest(
    val type: String,
    val tag: Uuid = Uuid.random(),
    @Serializable(with = JsonElementStringSerializer::class)
    val payload: JsonElement,
) :
    MilkyConsoleFromEvent.FromPlugin

object JsonElementStringSerializer : KSerializer<JsonElement> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("JsonElementString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: JsonElement) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): JsonElement =
        Json.parseToJsonElement(decoder.decodeString())
}

@Serializable
data class PluginApiResponse(val type: String, val tag: Uuid = Uuid.random(), val payload: ApiGeneralResponse) :
    MilkyConsoleFromEvent.FromHost
