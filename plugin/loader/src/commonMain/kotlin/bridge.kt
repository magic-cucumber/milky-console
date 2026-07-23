import co.touchlab.kermit.Logger
import kotlinx.cinterop.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.ntqqrev.milky.milkyJsonModule
import platform.milky_console_interop.MILKY_CONSOLE_HOST_ABI_VERSION
import platform.milky_console_interop.MILKY_RESULT_BUFFER_OVERFLOW
import platform.milky_console_interop.MILKY_RESULT_BUFFER_TOO_SHORT
import platform.milky_console_interop.MILKY_RESULT_INVALID_ARGUMENT
import platform.milky_console_interop.MILKY_RESULT_OK
import platform.milky_console_interop.MILKY_RESULT_TIMEOUT
import platform.milky_console_interop.milky_console_host_api
import platform.posix.malloc
import platform.posix.memcpy
import top.kagg886.milky.console.protocol.toPluginApiRequest
import top.kagg886.milky.console.util.eventbus.EventBus
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

private val logger = Logger.withTag("PluginLoader")

/** 分配并填充 host API 结构体，把插件的 send_message / wait_message_result 桥接到 EventBus。 */
@OptIn(ExperimentalForeignApi::class)
internal fun installHostApiBridge(): CPointer<milky_console_host_api> {
    val hostApi = malloc(sizeOf<milky_console_host_api>().toULong())!!.reinterpret<milky_console_host_api>()
    logger.v { "allocated host API struct: bytes=${sizeOf<milky_console_host_api>()}" }
    LoaderApplication.hostApi = hostApi
    PendingPluginApiRequests.initialize { request ->
        logger.v { "forwarding plugin API request to host: tag=${request.tag}" }
        EventBus.postBlocking(request)
    }

    hostApi.pointed.abi_version = MILKY_CONSOLE_HOST_ABI_VERSION
    hostApi.pointed.struct_size = sizeOf<milky_console_host_api>().toUInt()
    hostApi.pointed.send_message = staticCFunction { uin, type, message ->
        val type = type?.toKString() ?: return@staticCFunction cValue {
            logger.w { "send_message received null type; returning INVALID_ARGUMENT" }
            uuid[0] = 0
            result = MILKY_RESULT_INVALID_ARGUMENT
        }
        val text = message?.toKString() ?: return@staticCFunction cValue {
            logger.w { "send_message received null message; returning INVALID_ARGUMENT" }
            uuid[0] = 0
            result = MILKY_RESULT_INVALID_ARGUMENT
        }
        logger.v { "enter send_message: uin=$uin, type=$type, bytes=${text.encodeToByteArray().size}" }

        val event = try {
            text.toPluginApiRequest(type)!!.copy(uin = uin)
        } catch (_: Throwable) {
            logger.w { "send_message payload could not be decoded; returning INVALID_ARGUMENT" }
            return@staticCFunction cValue {
                uuid[0] = 0
                result = MILKY_RESULT_INVALID_ARGUMENT
            }
        }

        if (!PendingPluginApiRequests.register(event)) {
            logger.e { "send_message request registration failed: tag=${event.tag}" }
            return@staticCFunction cValue {
                uuid[0] = 0
                result = MILKY_RESULT_BUFFER_OVERFLOW
            }
        }
        logger.d { "send_message registered request: tag=${event.tag}, expected=true" }
        return@staticCFunction cValue {
            event.tag.toString().encodeToByteArray().usePinned { bytes ->
                memcpy(
                    uuid,
                    bytes.addressOf(0),
                    bytes.get().size.convert()
                )
                uuid[bytes.get().size] = 0
            }
            logger.v { "exit send_message successfully: tag=${event.tag}" }
        }
    }
    hostApi.pointed.wait_message_result = staticCFunction { id, timeout, buffer, size ->
        logger.v { "enter wait_message_result: timeoutMs=$timeout, bufferSize=$size" }
        if (id == null || timeout <= 0 || size <= 0u || buffer == null || id[36] != 0.toByte()) {
            logger.w { "wait_message_result received invalid arguments" }
            return@staticCFunction cValue {
                result = MILKY_RESULT_INVALID_ARGUMENT
                required_size = 0u
            }
        }
        val uuid = try {
            Uuid.parse(id.toKString())
        } catch (_: IllegalArgumentException) {
            logger.w { "wait_message_result received invalid UUID" }
            return@staticCFunction cValue {
                result = MILKY_RESULT_INVALID_ARGUMENT
                required_size = 0u
            }
        }

        val deferred = PendingPluginApiRequests.get(uuid) ?: return@staticCFunction cValue {
            logger.w { "wait_message_result has no pending request: tag=$uuid" }
            result = MILKY_RESULT_INVALID_ARGUMENT
            required_size = 0u
        }
        logger.d { "wait_message_result found pending request: tag=$uuid, expected=true" }

        val result = runBlocking {
            withTimeoutOrNull(timeout.milliseconds) {
                deferred.await()
            }
        }

        if (result == null) {
            logger.w { "wait_message_result timed out: tag=$uuid, timeoutMs=$timeout" }
            return@staticCFunction cValue {
                this.result = MILKY_RESULT_TIMEOUT
                required_size = 0u
            }
        }

        milkyJsonModule.encodeToString(result.payload).encodeToByteArray().usePinned {
            val byteCount = it.get().size
            if (size < byteCount.toUInt() + 1u) {
                logger.w { "wait_message_result buffer too short: tag=$uuid, capacity=$size, required=${byteCount + 1}" }
                return@staticCFunction cValue {
                    this.result = MILKY_RESULT_BUFFER_TOO_SHORT
                    required_size = byteCount.toULong() + 1u
                }
            }

            memcpy(
                buffer,
                it.addressOf(0),
                byteCount.convert()
            )

            buffer[byteCount] = 0
            PendingPluginApiRequests.remove(uuid)

            return@staticCFunction cValue {
                logger.d { "wait_message_result copied response: tag=$uuid, bytes=$byteCount, expected=true" }
                logger.v { "exit wait_message_result successfully: tag=$uuid" }
                this.result = MILKY_RESULT_OK
                required_size = byteCount.toULong() + 1u
            }
        }
    }
    return hostApi
}
