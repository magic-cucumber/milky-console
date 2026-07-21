@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package top.kagg886.milky.console.sample

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.serialization.encodeToString
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CName
import org.ntqqrev.milky.Event
import org.ntqqrev.milky.IncomingMessage
import org.ntqqrev.milky.IncomingSegment
import org.ntqqrev.milky.OutgoingSegment
import org.ntqqrev.milky.SendGroupMessageInput
import org.ntqqrev.milky.SendPrivateMessageInput
import org.ntqqrev.milky.milkyJsonModule
import platform.milky_console_interop.MILKY_CONSOLE_PLUGIN_ABI_VERSION
import platform.milky_console_interop.MILKY_FALSE
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

    val reply = when {
        command == "help" || command == "/help" -> HELP_TEXT
        command.startsWith("echo ") -> command.removePrefix("echo ")
        command.startsWith("/echo ") -> command.removePrefix("/echo ")
        else -> return
    }
    sendReply(event.selfId.toULong(),event.data, reply)
}

private fun sendReply(bot: ULong, message: IncomingMessage, text: String) {
    val segment = OutgoingSegment.Text(OutgoingSegment.Text.Data(text))
    val (endpoint, payload) = when (message) {
        is IncomingMessage.Friend -> "/send_private_message" to milkyJsonModule.encodeToString(
            SendPrivateMessageInput(userId = message.peerId, message = listOf(segment)),
        )

        is IncomingMessage.Group -> "/send_group_message" to milkyJsonModule.encodeToString(
            SendGroupMessageInput(groupId = message.peerId, message = listOf(segment)),
        )

        is IncomingMessage.Temp -> return
    }

    memScoped {
        hostApi?.pointed?.send_message?.invoke(
            bot,
            endpoint.cstr.getPointer(this),
            payload.cstr.getPointer(this),
        )
    }
}

private const val HELP_TEXT = "可用命令：help、echo <内容>"
