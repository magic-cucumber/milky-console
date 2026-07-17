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
import platform.posix.chdir
import platform.posix.exit
import platform.posix.free
import platform.posix.malloc
import top.kagg886.milky.console.protocol.HostClose
import top.kagg886.milky.console.protocol.HostEvent
import top.kagg886.milky.console.protocol.HostHandshakeRequest
import top.kagg886.milky.console.protocol.MilkyConsoleFromEvent
import top.kagg886.milky.console.protocol.PluginEvent
import top.kagg886.milky.console.protocol.PluginHandshakeRequest
import top.kagg886.milky.console.protocol.PluginHandshakeError
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

/**
 *                 receivePipe.sink.fd.toString(),
 *                 sendPipe.source.fd.toString(),
 *                 verified.libpath.toString(),
 *                 Json.encodeToString(verified.config),
 *                 registry.pluginDataPath(this@handshake).toString()
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalSerializationApi::class)
fun main(args: Array<String>): Unit = runBlocking(Dispatchers.IO) {
    require(args.size >= 3) { "usage: loader <sink-fd> <source-fd> <library> [config]" }
    val sink = IPCAnonymousPipe.fromSink(args[0].toULong())
    val source = IPCAnonymousPipe.fromSource(args[1].toULong())
    val libpath = args[2]
    val config = args[3] // "{}"
    val base = args[4]

    if (chdir(base) != 0) {
        exit(-1)
    }

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

    suspend fun reject(message: String, error: PluginHandshakeError? = null): Nothing {
        EventBus.post(PluginHandshakeResult.Rejected(message, error))
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
        reject("10秒内没有收到握手包，取消握手", PluginHandshakeError.TIMEOUT)
    }

    val loader = try {
        DLLoader(libpath)
    } catch (e: Throwable) {
        reject("无法加载插件动态库: ${e.message}", PluginHandshakeError.DYNAMIC_LIBRARY_LOAD_FAILED)
    }
    val getApi = try {
        loader.findSymbol<(UInt) -> CPointer<milky_console_plugin_api>?>("milky_plugin_get_api")
    } catch (e: Throwable) {
        loader.close()
        reject("无法查找到插件加载函数: ${e.message}", PluginHandshakeError.ENTRY_POINT_NOT_FOUND)
    }
    val pointer = getApi.invoke(MILKY_CONSOLE_HOST_ABI_VERSION)
    if (pointer == null || pointer.rawValue == NativePtr.NULL) {
        reject(
            "无法查找到插件加载函数: 插件加载函数返回值为空指针",
            PluginHandshakeError.NULL_PLUGIN_API,
        )
    }
    val api = pointer.pointed

    if (api.abi_version != MILKY_CONSOLE_HOST_ABI_VERSION) {
        reject(
            "插件ABI不匹配。本加载器期望: $MILKY_CONSOLE_HOST_ABI_VERSION，该插件的ABI为${api.abi_version}",
            PluginHandshakeError.ABI_MISMATCH,
        )
    }
    if (api.struct_size < sizeOf<milky_console_plugin_api>().toUInt()) {
        reject("插件API不匹配。", PluginHandshakeError.API_MISMATCH)
    }
    if (api.on_load == null) {
        reject("插件API缺少 on_load", PluginHandshakeError.MISSING_ON_LOAD)
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
        reject("插件初始化失败", PluginHandshakeError.INITIALIZATION_FAILED)
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
