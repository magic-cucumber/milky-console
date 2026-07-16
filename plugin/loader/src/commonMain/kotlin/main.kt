import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.NativePtr
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import org.ntqqrev.milky.milkyJsonModule
import platform.milky_console_interop.MILKY_CONSOLE_HOST_ABI_VERSION
import platform.milky_console_interop.MILKY_FALSE
import platform.milky_console_interop.MILKY_RESULT_INTERNAL_ERROR
import platform.milky_console_interop.MILKY_RESULT_INVALID_ARGUMENT
import platform.milky_console_interop.MILKY_RESULT_OK
import platform.milky_console_interop.milky_console_host_api
import platform.milky_console_interop.milky_console_plugin_api
import platform.posix.exit
import platform.posix.free
import platform.posix.malloc
import top.kagg886.milky.console.protocol.HostClose
import top.kagg886.milky.console.protocol.HostEvent
import top.kagg886.milky.console.protocol.HostHandshakeRequest
import top.kagg886.milky.console.protocol.MilkyConsoleFromEvent
import top.kagg886.milky.console.protocol.PluginEvent
import top.kagg886.milky.console.protocol.PluginHandshakeRequest
import top.kagg886.milky.console.protocol.PluginHandshakeResult
import top.kagg886.milky.console.protocol.PluginLog
import top.kagg886.milky.console.util.eventbus.EventBus
import top.kagg886.milky.console.util.eventbus.LRUCache
import top.kagg886.milky.console.util.pipe.IPCAnonymousPipe
import top.kagg886.milky.console.util.pipe.fromSink
import top.kagg886.milky.console.util.pipe.fromSource
import top.kagg886.milky.console.util.protocol.Packet
import top.kagg886.milky.console.util.protocol.isSplit
import top.kagg886.milky.console.util.protocol.merge
import top.kagg886.milky.console.util.protocol.readPacket
import top.kagg886.milky.console.util.protocol.toPacket
import top.kagg886.milky.console.util.protocol.writePacket
import top.kagg886.milky.console.util.readContent
import top.kagg886.saltify.console.util.dlloader.DLLoader
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@OptIn(ExperimentalForeignApi::class, ExperimentalSerializationApi::class)
fun main(args: Array<String>): Unit = runBlocking(Dispatchers.IO) {
    require(args.size >= 3) { "usage: loader <sink-fd> <source-fd> <library> [config]" }
    val sink = IPCAnonymousPipe.fromSink(args[0].toULong())
    val source = IPCAnonymousPipe.fromSource(args[1].toULong())
    val config = args.getOrNull(3) ?: "{}"

    // Register the sender before any loader event is posted.
    val terminalResultWritten = CompletableDeferred<Unit>()
    val sender = launch(start = CoroutineStart.UNDISPATCHED) {
        EventBus.subscribe<MilkyConsoleFromEvent.FromPlugin>().collect { event ->
            event.toPacket().forEach(sink::writePacket)
            if (event is PluginHandshakeResult) terminalResultWritten.complete(Unit)
        }
    }

    Logger.setLogWriters(object : LogWriter() {
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            val text = throwable?.let { "$message\n${it.stackTraceToString()}" } ?: message
            EventBus.tryPost(PluginLog(severity.ordinal, tag, text))
        }
    })

    // This is the readiness condition represented by PluginHandshakeRequest.
    val hostHandshakeRequest = async(start = CoroutineStart.UNDISPATCHED) {
        EventBus.subscribe<HostHandshakeRequest>().first()
    }

    val receiver = launch {
        val packetsByGroup = LRUCache.create<Uuid, List<Packet>>(1.minutes, 16 * 1024 * 1024) { _, packets ->
            packets.sumOf { it.data.size }
        }
        while (isActive) {
            val packet = source.readPacket()
            val merged = if (!packet.isSplit) {
                packet
            } else {
                val group = requireNotNull(packet.group)
                val packets = packetsByGroup.getOrPut(group) { emptyList() }!! + packet
                if (packets.size != packet.size) {
                    packetsByGroup.put(group, packets)
                    continue
                }
                val ordered = packets.filter { it.index != null }.sortedBy { it.index }
                if (!ordered.indices.all { ordered[it].index == it }) {
                    packetsByGroup.put(group, packets)
                    continue
                }
                packetsByGroup.remove(group)
                ordered.merge()
            }
            EventBus.post(merged.data.readContent<MilkyConsoleFromEvent.FromHost>())
        }
    }

    suspend fun reject(message: String): Nothing {
        EventBus.post(PluginHandshakeResult.Rejected(message))
        terminalResultWritten.await()
        receiver.cancel()
        sender.cancel()
        sink.close()
        source.close()
        exit(1)
        error("unreachable")
    }

    // Sender and HostHandshakeRequest listener are now both active.
    EventBus.post(PluginHandshakeRequest)
    if (withTimeoutOrNull(10.seconds) { hostHandshakeRequest.await() } == null) {
        reject("10秒内没有收到握手包，取消握手")
    }

    val loader = try {
        DLLoader(args[2])
    } catch (e: Throwable) {
        reject("无法加载插件动态库: ${e.message}")
    }
    val api = try {
        val pointer = loader
            .findSymbol<(UInt) -> CPointer<milky_console_plugin_api>?>("milky_plugin_get_api")
            .invoke(MILKY_CONSOLE_HOST_ABI_VERSION)
        if (pointer == null || pointer.rawValue == NativePtr.NULL) {
            error("插件加载函数返回值为空指针")
        }
        pointer.pointed
    } catch (e: Throwable) {
        loader.close()
        reject("无法查找到插件加载函数: ${e.message}")
    }

    if (api.abi_version != MILKY_CONSOLE_HOST_ABI_VERSION) {
        loader.close()
        reject("插件ABI不匹配。本加载器期望: $MILKY_CONSOLE_HOST_ABI_VERSION，该插件的ABI为${api.abi_version}")
    }
    if (api.struct_size < sizeOf<milky_console_plugin_api>().toUInt()) {
        loader.close()
        reject("插件API不匹配。")
    }
    if (api.on_load == null) {
        loader.close()
        reject("插件API缺少 on_load")
    }

    val hostApi = malloc(sizeOf<milky_console_host_api>().toULong())!!.reinterpret<milky_console_host_api>()
    hostApi.pointed.abi_version = MILKY_CONSOLE_HOST_ABI_VERSION
    hostApi.pointed.struct_size = sizeOf<milky_console_host_api>().toUInt()
    hostApi.pointed.send_message = staticCFunction { message ->
        val text = message?.toKString() ?: return@staticCFunction MILKY_RESULT_INVALID_ARGUMENT
        val event = try {
            PluginEvent(milkyJsonModule.decodeFromString(text))
        } catch (_: Throwable) {
            return@staticCFunction MILKY_RESULT_INVALID_ARGUMENT
        }
        if (EventBus.tryPost(event)) MILKY_RESULT_OK else MILKY_RESULT_INTERNAL_ERROR
    }

    val loaded = memScoped {
        api.on_load!!.invoke(config.cstr.getPointer(this), hostApi)
    }
    if (loaded == MILKY_FALSE) {
        free(hostApi)
        loader.close()
        reject("插件初始化失败")
    }

    // Ready is sent only after all runtime listeners are registered.
    val hostEvents = launch(start = CoroutineStart.UNDISPATCHED) {
        EventBus.subscribe<HostEvent>().collect { message ->
            memScoped {
                api.on_message?.invoke(milkyJsonModule.encodeToString(message.event).cstr.getPointer(this))
            }
        }
    }
    val hostClose = async(start = CoroutineStart.UNDISPATCHED) {
        EventBus.subscribe<HostClose>().first()
    }
    EventBus.post(PluginHandshakeResult.Ready)
    terminalResultWritten.await()

    hostClose.await()
    hostEvents.cancelAndJoin()
    val exitCode = try {
        api.on_unload?.invoke() ?: 0
    } finally {
        receiver.cancel()
        sender.cancel()
        free(hostApi)
        sink.close()
        source.close()
        loader.close()
    }
    exit(exitCode)
}
