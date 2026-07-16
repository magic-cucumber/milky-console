import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import org.ntqqrev.milky.milkyJsonModule
import platform.milky_console_interop.*
import platform.posix.exit
import platform.posix.free
import platform.posix.malloc
import top.kagg886.milky.console.protocol.*
import top.kagg886.milky.console.util.eventbus.EventBus
import top.kagg886.milky.console.util.eventbus.LRUCache
import top.kagg886.milky.console.util.pipe.IPCAnonymousPipe
import top.kagg886.milky.console.util.pipe.fromSink
import top.kagg886.milky.console.util.pipe.fromSource
import top.kagg886.milky.console.util.protocol.*
import top.kagg886.milky.console.util.readContent
import top.kagg886.saltify.console.util.dlloader.DLLoader
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 *   arguments(sendPipe.sink.fd.toString(),receivePipe.source.fd.toString(),libpath.toString(),config.toString)
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalSerializationApi::class)
fun main(args: Array<String>): Unit = runBlocking(Dispatchers.IO) {
    val sink = IPCAnonymousPipe.fromSink(args[0].toULong())
    val source = IPCAnonymousPipe.fromSource(args[1].toULong())
    val config = args.getOrNull(3) ?: "{}"

    //握手
    val handshakeRequest = async(start = CoroutineStart.UNDISPATCHED) {
        val result = withTimeoutOrNull(10.seconds) {
            EventBus.subscribe<HostHandshakeRequest>().first()
            true
        }
        result ?: false
    }

    suspend fun writeEvent(event: MilkyConsoleFromEvent.FromPlugin) {
        event.toPacket().forEach { packet ->
            //write packet 非挂起。主动yield挂起实现乱序发送
            sink.writePacket(packet)
            yield()
        }
    }

    val sender = launch(start = CoroutineStart.UNDISPATCHED) {
        EventBus.subscribe<MilkyConsoleFromEvent.FromPlugin>().collect(::writeEvent)
    }
    val receiver = launch(start = CoroutineStart.UNDISPATCHED) {
        val packetsByGroup = LRUCache.create<Uuid, List<Packet>>(1.minutes, 16 * 1024 * 1024) { _, packets ->
            packets.sumOf { it.data.size }
        }
        while (isActive) {
            yield()
            val packet = source.readPacket()
            val mergedPacket = if (packet.isSplit) {
                val group = requireNotNull(packet.group)
                val packets = packetsByGroup.getOrPut(group) { emptyList() }!! + packet
                if (packets.size != packet.size) {
                    packetsByGroup.put(group, packets)
                    continue
                }

                val orderedPackets = packets.filter { it.index != null }.sortedBy { it.index!! }
                if (orderedPackets.map { it.index!! }.run { isContinuous() && first() == 0 }) {
                    packetsByGroup.remove(group)
                    orderedPackets.merge()
                } else {
                    packetsByGroup.put(group, packets)
                    continue
                }
            } else {
                packet
            }
            EventBus.post(mergedPacket.data.readContent<MilkyConsoleFromEvent.FromHost>())
        }
    }

    if (!handshakeRequest.await()) {
        EventBus.post(PluginHandshakeResult.Rejected("10秒内没有收到握手包，取消握手"))
        yield()
        sender.cancel()
        receiver.cancel()
        return@runBlocking
    }

    //到这里我们收到了握手包，开始初始化dlLoader
    val loader = DLLoader(args[2])
    val api = try {
        val result = loader.findSymbol<(UInt) -> CPointer<milky_console_plugin_api>?>("milky_plugin_get_api")
            .invoke(MILKY_CONSOLE_HOST_ABI_VERSION)
        if (result == null || result.rawValue == NativePtr.NULL) {
            error("插件加载函数返回值为空指针")
        }
        result.pointed
    } catch (e: Exception) {
        EventBus.post(PluginHandshakeResult.Rejected("无法查找到插件加载函数: ${e.message}"))
        yield()
        sender.cancel()
        receiver.cancel()
        return@runBlocking
    }

    if (api.abi_version != MILKY_CONSOLE_HOST_ABI_VERSION) {
        EventBus.post(PluginHandshakeResult.Rejected("插件ABI不匹配。本加载器期望: ${MILKY_CONSOLE_HOST_ABI_VERSION}，该插件的ABI为${api.abi_version}"))
        yield()
        sender.cancel()
        receiver.cancel()
        return@runBlocking
    }

    if (api.struct_size < sizeOf<milky_console_plugin_api>().toUInt()) {
        EventBus.post(PluginHandshakeResult.Rejected("插件API不匹配。"))
        yield()
        sender.cancel()
        receiver.cancel()
        return@runBlocking
    }

    //构造api
    val hostApi = malloc(sizeOf<milky_console_host_api>().toULong())!!.reinterpret<milky_console_host_api>()
    hostApi.pointed.abi_version = MILKY_CONSOLE_HOST_ABI_VERSION
    hostApi.pointed.struct_size = sizeOf<milky_console_host_api>().toUInt()
    hostApi.pointed.send_message = staticCFunction { message ->
        val message = message?.toKString() ?: return@staticCFunction MILKY_RESULT_INVALID_ARGUMENT
        val event = try {
            PluginEvent(milkyJsonModule.decodeFromString(message))
        } catch (_: Exception) {
            return@staticCFunction MILKY_RESULT_INVALID_ARGUMENT
        }
        val result = EventBus.tryPost(event)
        if (result) MILKY_RESULT_OK else MILKY_RESULT_INTERNAL_ERROR
    }
    val onLoadResult = memScoped {
        api.on_load?.invoke(config.cstr.getPointer(this), hostApi)
    }

    if (onLoadResult == MILKY_FALSE) {
        EventBus.post(PluginHandshakeResult.Rejected("插件初始化失败"))
        yield()
        sender.cancel()
        receiver.cancel()
        return@runBlocking
    }

    //消息循环
    var handshakeSuccess = false
    var closedByHost = false
    val ex = try {
        coroutineScope {
            launch(start = CoroutineStart.UNDISPATCHED) {
                EventBus.subscribe<HostEvent>().collect { message ->
                    memScoped {
                        api.on_message?.invoke(milkyJsonModule.encodeToString(message.event).cstr.getPointer(this))
                    }
                }
            }

            launch(start = CoroutineStart.UNDISPATCHED) {
                EventBus.subscribe<HostClose>().collect { message ->
                    closedByHost = true
                    this@coroutineScope.cancel(CancellationException(message.reason))
                }
            }
            EventBus.post(PluginHandshakeResult.Ready)
            handshakeSuccess = true
        }
        null
    } catch (e: Throwable) {
        e
    }

    if (handshakeSuccess && !closedByHost) {
        val error = ex?.cause ?: ex
        EventBus.post(PluginClosed(error?.message ?: "插件客户端已关闭", error?.stackTraceToString()))
        yield()
    }

    sender.join()
    receiver.join()

    //结束前销毁程序
    var code: Int
    try {
        code = api.on_unload?.invoke() ?: 0
    } finally {
        free(hostApi)
        sink.close()
        source.close()
        loader.close()
    }

    exit(code)
}
