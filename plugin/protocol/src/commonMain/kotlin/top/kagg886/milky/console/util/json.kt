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
import co.touchlab.kermit.Logger
import top.kagg886.milky.console.protocol.MilkyConsoleFromEvent

@PublishedApi
internal val logger = Logger.withTag("ProtocolJson")

/** JSON codec used for every host-loader packet payload. */
val MilkyConsoleJson = Json

inline fun <reified T> Buffer.readContent(format: Json = MilkyConsoleJson): T {
    logger.v { "enter readContent<${T::class.simpleName}>: bytes=$size" }
    val content = readUtf8()
    logger.d { "read packet payload text: chars=${content.length}, expectedNonEmpty=${content.isNotEmpty()}" }
    val result = format.decodeFromString<T>(content)
    logger.v { "exit readContent<${T::class.simpleName}> successfully" }
    return result
}

fun <T> Buffer.readContent(serializer: KSerializer<T>, format: Json = MilkyConsoleJson): T {
    logger.v { "enter readContent(serializer): bytes=$size, descriptor=${serializer.descriptor.serialName}" }
    val content = readUtf8()
    logger.d { "read packet payload text with serializer: chars=${content.length}, expectedNonEmpty=${content.isNotEmpty()}" }
    val result = format.decodeFromString(serializer, content)
    logger.v { "exit readContent(serializer) successfully: descriptor=${serializer.descriptor.serialName}" }
    return result
}

inline fun <reified T> T.toBuffer(format: Json = MilkyConsoleJson): Buffer = Buffer().apply {
    logger.v { "enter toBuffer<${T::class.simpleName}>" }
    val content = format.encodeToString(this@toBuffer)
    writeUtf8(content)
    logger.d { "encoded generic payload to buffer: chars=${content.length}, bytes=$size, expectedNonEmpty=${size > 0L}" }
    logger.v { "exit toBuffer<${T::class.simpleName}> successfully: bytes=$size" }
}

fun MilkyConsoleFromEvent.FromHost.toBuffer(format: Json = MilkyConsoleJson): Buffer = Buffer().apply {
    logger.v { "enter FromHost.toBuffer: type=${this@toBuffer::class.simpleName}" }
    val content = format.encodeToString(MilkyConsoleFromEvent.FromHost.serializer(), this@toBuffer)
    writeUtf8(content)
    logger.i { "encoded host protocol event: type=${this@toBuffer::class.simpleName}, bytes=$size" }
    logger.d { "host event buffer encode result: chars=${content.length}, expectedNonEmpty=${size > 0L}" }
    logger.v { "exit FromHost.toBuffer successfully: type=${this@toBuffer::class.simpleName}" }
}

fun MilkyConsoleFromEvent.FromPlugin.toBuffer(format: Json = MilkyConsoleJson): Buffer = Buffer().apply {
    logger.v { "enter FromPlugin.toBuffer: type=${this@toBuffer::class.simpleName}" }
    val content = format.encodeToString(MilkyConsoleFromEvent.FromPlugin.serializer(), this@toBuffer)
    writeUtf8(content)
    logger.i { "encoded plugin protocol event: type=${this@toBuffer::class.simpleName}, bytes=$size" }
    logger.d { "plugin event buffer encode result: chars=${content.length}, expectedNonEmpty=${size > 0L}" }
    logger.v { "exit FromPlugin.toBuffer successfully: type=${this@toBuffer::class.simpleName}" }
}


/** Serializes only this field with Milky's JSON configuration as JSON, not a JSON string. */
object MilkyElementSerializer : KSerializer<JsonElement> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: JsonElement) {
        logger.v { "enter MilkyElementSerializer.serialize: encoder=${encoder::class.simpleName}" }
        if (encoder !is JsonEncoder) {
            logger.e { "exit MilkyElementSerializer.serialize unsuccessfully: JSON encoder required, actual=${encoder::class.simpleName}" }
        }
        require(encoder is JsonEncoder) { "JsonElementMilkySerializer supports JSON only" }
        val encoded = milkyJsonModule.encodeToJsonElement(value)
        encoder.encodeJsonElement(encoded)
        logger.d { "serialized Milky JsonElement field: expectedJson=true" }
        logger.v { "exit MilkyElementSerializer.serialize successfully" }
    }

    override fun deserialize(decoder: Decoder): JsonElement {
        logger.v { "enter MilkyElementSerializer.deserialize: decoder=${decoder::class.simpleName}" }
        if (decoder !is JsonDecoder) {
            logger.e { "exit MilkyElementSerializer.deserialize unsuccessfully: JSON decoder required, actual=${decoder::class.simpleName}" }
        }
        require(decoder is JsonDecoder) { "JsonElementMilkySerializer supports JSON only" }
        val decoded = milkyJsonModule.decodeFromJsonElement<JsonElement>(decoder.decodeJsonElement())
        logger.d { "deserialized Milky JsonElement field: expectedJson=true" }
        logger.v { "exit MilkyElementSerializer.deserialize successfully" }
        return decoded
    }
}

/** Serializes only this field with Milky's JSON configuration as JSON, not a JSON string. */
object MilkyApiResponseSerializer : KSerializer<ApiGeneralResponse> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ApiGeneralResponse) {
        logger.v { "enter MilkyApiResponseSerializer.serialize: encoder=${encoder::class.simpleName}" }
        if (encoder !is JsonEncoder) {
            logger.e { "exit MilkyApiResponseSerializer.serialize unsuccessfully: JSON encoder required, actual=${encoder::class.simpleName}" }
        }
        require(encoder is JsonEncoder) { "MilkyApiResponseSerializer supports JSON only" }
        val encoded = milkyJsonModule.encodeToJsonElement(value)
        encoder.encodeJsonElement(encoded)
        logger.d { "serialized Milky API response field: expectedJson=true" }
        logger.v { "exit MilkyApiResponseSerializer.serialize successfully" }
    }

    override fun deserialize(decoder: Decoder): ApiGeneralResponse {
        logger.v { "enter MilkyApiResponseSerializer.deserialize: decoder=${decoder::class.simpleName}" }
        if (decoder !is JsonDecoder) {
            logger.e { "exit MilkyApiResponseSerializer.deserialize unsuccessfully: JSON decoder required, actual=${decoder::class.simpleName}" }
        }
        require(decoder is JsonDecoder) { "MilkyApiResponseSerializer supports JSON only" }
        val decoded = milkyJsonModule.decodeFromJsonElement<ApiGeneralResponse>(decoder.decodeJsonElement())
        logger.d { "deserialized Milky API response field: expectedJson=true" }
        logger.v { "exit MilkyApiResponseSerializer.deserialize successfully" }
        return decoded
    }
}
