package top.kagg886.milky.console.util

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json
import okio.Buffer

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/14 13:31
 * ================================================
 */

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> Buffer.readContent(format: Cbor = Cbor): T = format.decodeFromByteArray(readByteArray())

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> T.toBuffer(format: Cbor = Cbor) : Buffer = Buffer().apply {
    write(format.encodeToByteArray(this))
}
