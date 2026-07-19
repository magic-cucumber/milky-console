package top.kagg886.milky.console.util

import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import okio.Buffer
import org.ntqqrev.milky.ApiGeneralResponse
import org.ntqqrev.milky.milkyJsonModule
import top.kagg886.milky.console.protocol.MilkyConsoleFromEvent

/** JSON codec used for every host-loader packet payload. */
val MilkyConsoleJson = Json

inline fun <reified T> Buffer.readContent(format: Json = MilkyConsoleJson): T =
    format.decodeFromString(readUtf8())

fun <T> Buffer.readContent(serializer: KSerializer<T>, format: Json = MilkyConsoleJson): T =
    format.decodeFromString(serializer, readUtf8())

inline fun <reified T> T.toBuffer(format: Json = MilkyConsoleJson): Buffer = Buffer().apply {
    writeUtf8(format.encodeToString(this@toBuffer))
}

fun MilkyConsoleFromEvent.FromHost.toBuffer(format: Json = MilkyConsoleJson): Buffer = Buffer().apply {
    writeUtf8(format.encodeToString(MilkyConsoleFromEvent.FromHost.serializer(), this@toBuffer))
}

fun MilkyConsoleFromEvent.FromPlugin.toBuffer(format: Json = MilkyConsoleJson): Buffer = Buffer().apply {
    writeUtf8(format.encodeToString(MilkyConsoleFromEvent.FromPlugin.serializer(), this@toBuffer))
}


/** Serializes only this field with Milky's JSON configuration as JSON, not a JSON string. */
object MilkyElementSerializer : KSerializer<JsonElement> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: JsonElement) {
        require(encoder is JsonEncoder) { "JsonElementMilkySerializer supports JSON only" }
        encoder.encodeJsonElement(milkyJsonModule.encodeToJsonElement(value))
    }

    override fun deserialize(decoder: Decoder): JsonElement {
        require(decoder is JsonDecoder) { "JsonElementMilkySerializer supports JSON only" }
        return milkyJsonModule.decodeFromJsonElement(decoder.decodeJsonElement())
    }
}

/** Serializes only this field with Milky's JSON configuration as JSON, not a JSON string. */
object MilkyApiResponseSerializer : KSerializer<ApiGeneralResponse> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ApiGeneralResponse) {
        require(encoder is JsonEncoder) { "MilkyApiResponseSerializer supports JSON only" }
        encoder.encodeJsonElement(milkyJsonModule.encodeToJsonElement(value))
    }

    override fun deserialize(decoder: Decoder): ApiGeneralResponse {
        require(decoder is JsonDecoder) { "MilkyApiResponseSerializer supports JSON only" }
        return milkyJsonModule.decodeFromJsonElement(decoder.decodeJsonElement())
    }
}
