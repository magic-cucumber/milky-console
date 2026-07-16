import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.ExperimentalSerializationApi
import okio.Buffer
import org.ntqqrev.milky.milkyJsonModule
import platform.milky_console_interop.*
import platform.posix.exit
import platform.posix.free
import platform.posix.malloc
import top.kagg886.milky.console.protocol.ClientHandshakeRequest
import top.kagg886.milky.console.protocol.ClientHandshakeResult
import top.kagg886.milky.console.protocol.MilkyConsoleEvent
import top.kagg886.milky.console.util.eventbus.EventBus
import top.kagg886.milky.console.util.eventbus.LRUCache
import top.kagg886.milky.console.util.pipe.IPCAnonymousPipe
import top.kagg886.milky.console.util.pipe.fromSink
import top.kagg886.milky.console.util.pipe.fromSource
import top.kagg886.milky.console.util.protocol.Packet
import top.kagg886.milky.console.util.protocol.isSplit
import top.kagg886.milky.console.util.protocol.merge
import top.kagg886.milky.console.util.protocol.readPacket
import top.kagg886.milky.console.util.protocol.split
import top.kagg886.milky.console.util.protocol.toPacket
import top.kagg886.milky.console.util.protocol.writePacket
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

    //开始握手
    val handshakeRequestSuccess = withTimeoutOrNull(10.seconds) {
        source.readPacket().data.readContent<ClientHandshakeRequest>()
        true
    } ?: false

    if (!handshakeRequestSuccess) {
        ClientHandshakeResult.Failed("10秒内没有收到握手包，取消握手").toPacket().forEach { sink.writePacket(it) }
        return@runBlocking
    }

    val loader = DLLoader(args[2])
    val api = try {
        val result = loader.findSymbol<(UInt) -> CPointer<milky_console_plugin_api>?>("milky_plugin_get_api")
            .invoke(MILKY_CONSOLE_HOST_ABI_VERSION)
        if (result == null || result.rawValue == NativePtr.NULL) {
            error("插件加载函数返回值为空指针")
        }
        result.pointed
    } catch (e: Exception) {
        ClientHandshakeResult.Failed("无法查找到插件加载函数: ${e.message}").toPacket().forEach { sink.writePacket(it) }
        return@runBlocking
    }

    if (api.abi_version != MILKY_CONSOLE_HOST_ABI_VERSION) {
        ClientHandshakeResult.Failed("插件ABI不匹配。本加载器期望: ${MILKY_CONSOLE_HOST_ABI_VERSION}，该插件的ABI为${api.abi_version}")
            .toPacket().forEach { sink.writePacket(it) }
        return@runBlocking
    }

    if (api.struct_size < sizeOf<milky_console_plugin_api>().toUInt()) {
        ClientHandshakeResult.Failed("插件API不匹配。").toPacket().forEach { sink.writePacket(it) }
        return@runBlocking
    }


    val hostApi = malloc(sizeOf<milky_console_host_api>().toULong())!!.reinterpret<milky_console_host_api>()
    hostApi.pointed.abi_version = MILKY_CONSOLE_HOST_ABI_VERSION
    hostApi.pointed.struct_size = sizeOf<milky_console_host_api>().toUInt()
    hostApi.pointed.send_message = staticCFunction { message ->
        val message = message?.toKString() ?: return@staticCFunction MILKY_RESULT_INVALID_ARGUMENT
        val result = EventBus.tryPost(SendPacket(Packet(data = Buffer().writeUtf8(message))))
        if (result) MILKY_RESULT_OK else MILKY_RESULT_INTERNAL_ERROR
    }

    //消息循环
    val ex = try {
        coroutineScope {
            //发送请求，拆包发送
            launch(start = CoroutineStart.UNDISPATCHED) {
                EventBus.subscribe<SendPacket>().collect { packet ->
                    //write packet 非挂起。主动yield挂起实现乱序发送
                    packet.packet.split().forEach { sink.writePacket(it); yield() }
                }
            }


            val flow = MutableSharedFlow<Packet>()
            launch(start = CoroutineStart.UNDISPATCHED) {
                flow.collect { packet ->
                    val message = try {
                        packet.data.readContent<MilkyConsoleEvent>()
                    } catch (_: Exception) {
                        this@coroutineScope.cancel()
                        return@collect
                    }

                    when (message) {
                        is MilkyConsoleEvent.ProtocolEvent -> memScoped {
                            api.on_message?.invoke(milkyJsonModule.encodeToString(message.event).cstr.getPointer(this))
                        }

                        is MilkyConsoleEvent.InternalEvent -> {
                            EventBus.post(message)
                        }
                    }
                }
            }

            //接受响应，自动组包
            launch {
                //合并用 packet cache
                val hash = LRUCache.create<Uuid, List<Packet>>(1.minutes,16 * 1024 * 1024) { _, v ->
                    v.sumOf { it.data.size }
                }
                while (isActive) {
                    yield()
                    val packet = try {
                        source.readPacket()
                    } catch (_: Exception) {
                        //通讯错误取消进入关闭流程
                        this@coroutineScope.cancel()
                        break
                    }
                    if (packet.isSplit) {
                        val k = packet.group!!
                        val packets = hash.getOrPut(k) { emptyList() }!! + packet
                        //包数目相同
                        if (packets.size == packet.size) {
                            //包index连续且从0开始
                            val prepareMergePackets = packets.filter { it.index != null }.sortedBy { it.index!! }
                            if (prepareMergePackets.map { it.index!! }.run { isContinuous() && first() == 0 }) {
                                flow.emit(prepareMergePackets.merge())
                                hash.remove(k)
                                continue
                            }
                        }
                        hash.put(k, packets)
                        continue
                    }
                    flow.emit(packet)

                }
            }

            val onLoadResult = memScoped {
                api.on_load?.invoke(config.cstr.getPointer(this), hostApi)
            }
            if (onLoadResult == MILKY_FALSE) {
                ClientHandshakeResult.Failed("插件初始化失败").toPacket().forEach { sink.writePacket(it) }
                cancel()
                return@coroutineScope
            }

            ClientHandshakeResult.Success.toPacket().forEach { sink.writePacket(it) }
        }
        null
    } catch (e: Throwable) {
        e
    }

    //TODO 通知主进程插件即将失效

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
