@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package top.kagg886.milky.console.sample

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import kotlinx.serialization.encodeToString
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CName
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import org.ntqqrev.milky.Event
import org.ntqqrev.milky.IncomingMessage
import org.ntqqrev.milky.IncomingSegment
import org.ntqqrev.milky.OutgoingSegment
import org.ntqqrev.milky.SendGroupMessageInput
import org.ntqqrev.milky.SendPrivateMessageInput
import org.ntqqrev.milky.milkyJsonModule
import platform.milky_console_interop.MILKY_CONSOLE_PLUGIN_ABI_VERSION
import platform.milky_console_interop.MILKY_FALSE
import platform.milky_console_interop.MILKY_RESULT_BUFFER_TOO_SHORT
import platform.milky_console_interop.MILKY_RESULT_OK
import platform.milky_console_interop.MILKY_TRUE
import platform.milky_console_interop.milky_console_host_api
import platform.milky_console_interop.milky_console_plugin_api
import platform.posix.malloc

private var hostApi: CPointer<milky_console_host_api>? = null

private val pluginApi = malloc(sizeOf<milky_console_plugin_api>().toULong())!!
    .reinterpret<milky_console_plugin_api>()
    .apply { pointed.apply {
    abi_version = MILKY_CONSOLE_PLUGIN_ABI_VERSION
    struct_size = sizeOf<milky_console_plugin_api>().toUInt()
    on_load = staticCFunction(::onLoad)
    on_unload = staticCFunction(::onUnload)
    on_message = staticCFunction(::onMessage)
} }

/** Entry point required by [milky_console.h](../../../api/include/milky_console.h). */
@OptIn(ExperimentalNativeApi::class)
@CName("milky_plugin_get_api")
fun milkyPluginGetApi(requestedAbiVersion: UInt): CPointer<milky_console_plugin_api>? =
    pluginApi.takeIf { requestedAbiVersion == MILKY_CONSOLE_PLUGIN_ABI_VERSION }

private fun onLoad(
    @Suppress("UNUSED_PARAMETER") configJson: CPointer<ByteVar>?,
    api: CPointer<milky_console_host_api>?,
): Int {
    hostApi = api
    return if (api == null) MILKY_FALSE else MILKY_TRUE
}

private fun onUnload(): Int {
    hostApi = null
    return MILKY_RESULT_OK
}

private fun onMessage(message: CPointer<ByteVar>?) {
    val event = message?.toKString()
        ?.let { runCatching { milkyJsonModule.decodeFromString<Event>(it) }.getOrNull() }
        as? Event.MessageReceive
        ?: return

    val command = event.segments.filterIsInstance<IncomingSegment.Text>()
        .joinToString(separator = "") { it.text }
        .trim()

    if (command != "help" && command != "/help") return

    val entries = FileSystem.SYSTEM.canonicalize(".".toPath())
    sendReply(event.selfId.toULong(),event.data, "可用命令：help\n当前目录（.）：\n$entries")
}

private fun sendReply(bot: ULong, message: IncomingMessage, text: String) {
    val (endpoint, payload) = replyRequest(message, text) ?: return
    val result = send_message(bot, endpoint, payload) ?: return
    val (_, resultPayload) = replyRequest(message, result) ?: return

    // Send the API response to the same conversation, but do not forward the
    // acknowledgement of this second request to avoid an endless reply loop.
    send_message(bot, endpoint, resultPayload)
}

private fun replyRequest(message: IncomingMessage, text: String): Pair<String, String>? {
    val segment = OutgoingSegment.Text(OutgoingSegment.Text.Data(text))
    return when (message) {
        is IncomingMessage.Friend -> "/send_private_message" to milkyJsonModule.encodeToString(
            SendPrivateMessageInput(userId = message.peerId, message = listOf(segment)),
        )

        is IncomingMessage.Group -> "/send_group_message" to milkyJsonModule.encodeToString(
            SendGroupMessageInput(groupId = message.peerId, message = listOf(segment)),
        )

        is IncomingMessage.Temp -> null
    }
}

/** Sends a host API request and waits until its result has been returned. */
private fun send_message(bot: ULong, endpoint: String, payload: String): String? = memScoped {
    val api = hostApi?.pointed ?: return@memScoped null
    val sent = api.send_message?.invoke(
        bot,
        endpoint.cstr.getPointer(this),
        payload.cstr.getPointer(this),
    ) ?: return@memScoped null
    val requestId = sent.useContents {
        uuid.toKString().takeIf { result == MILKY_RESULT_OK }
    } ?: return@memScoped null

    wait_message_result(api, requestId)
}

private fun wait_message_result(api: milky_console_host_api, requestId: String): String? = memScoped {
    var bufferSize = INITIAL_RESPONSE_BUFFER_SIZE
    while (true) {
        val buffer = allocArray<ByteVar>(bufferSize)
        val waited = api.wait_message_result?.invoke(
            requestId.cstr.getPointer(this),
            SEND_TIMEOUT_MS,
            buffer,
            bufferSize.toULong(),
        ) ?: return@memScoped null

        val result = waited.useContents { result }
        when (result) {
            MILKY_RESULT_OK -> return@memScoped buffer.toKString()
            MILKY_RESULT_BUFFER_TOO_SHORT -> {
                bufferSize = waited.useContents { required_size.toInt() }
                if (bufferSize <= 0) return@memScoped null
            }
            else -> return@memScoped null
        }
    }
    null
}

private const val SEND_TIMEOUT_MS = 5_000
private const val INITIAL_RESPONSE_BUFFER_SIZE = 256
