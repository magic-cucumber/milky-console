import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.ExperimentalSerializationApi
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

private val log = Logger.withTag("Loader")

/**
 *   arguments(sendPipe.sink.fd.toString(),receivePipe.source.fd.toString(),libpath.toString(),config.toString)
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalSerializationApi::class)
fun main(args: Array<String>): Unit = runBlocking(Dispatchers.IO) {
    Logger.setLogWriters(
        object : LogWriter() {
            override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                val msg = if (throwable != null) "$message\n${throwable.stackTraceToString()}" else message
                EventBus.tryPost(PluginLog(severity.ordinal, tag, msg))
            }
        }
    )

    log.d { "[step 1/7] [group: logger-init] logger redirected: writers=[EventBusLogWriter], minSeverity=${Logger.config.minSeverity}" }

    // -------------------- 2. 解析 pipe FD 与 config --------------------
    log.i { "[step 2/7] parsing pipe FDs and config" }
    val sink = IPCAnonymousPipe.fromSink(args[0].toULong())
    val source = IPCAnonymousPipe.fromSource(args[1].toULong())
    val config = args.getOrNull(3) ?: "{}"
    log.v { "sink fd=${args[0]}, source fd=${args[1]}" }
    log.v { "config = ${if (args.getOrNull(3) != null) "provided" else "empty default"}" }
    log.d { "[group: pipe-parse] sink and source created from inherited FDs, config parsed" }

    // -------------------- 3. 启动发送协程（必须早于任何 EventBus post） --------------------
    log.i { "[step 3/7] starting sender coroutine (FromPlugin -> pipe)" }

    suspend fun writeEvent(event: MilkyConsoleFromEvent.FromPlugin) {
        event.toPacket().forEach { packet ->
            sink.writePacket(packet)
            yield()
        }
    }

    val handshakeRejectionSent = CompletableDeferred<Unit>()
    val sender = launch(start = CoroutineStart.UNDISPATCHED) {
        log.i { ">>> sender coroutine enter, subscribing to MilkyConsoleFromEvent.FromPlugin" }
        EventBus.subscribe<MilkyConsoleFromEvent.FromPlugin>().collect { event ->
            writeEvent(event)
            if (event is PluginHandshakeResult.Rejected) {
                handshakeRejectionSent.complete(Unit)
            }
        }
        log.i { "<<< sender coroutine exit" }
    }
    log.d { "[group: coroutine-start] sender launched, EventBus subscription active" }

    // -------------------- 4. 启动接收协程 --------------------
    log.i { "[step 4/7] starting receiver coroutine (pipe -> EventBus)" }
    val receiver = launch(start = CoroutineStart.UNDISPATCHED) {
        log.i { ">>> receiver coroutine enter" }
        val packetsByGroup = LRUCache.create<Uuid, List<Packet>>(1.minutes, 16 * 1024 * 1024) { _, packets ->
            packets.sumOf { it.data.size }
        }
        while (isActive) {
            yield()
            val packet = source.readPacket()
            log.v { "receiver: read packet, isSplit=${packet.isSplit}" }
            val mergedPacket = if (packet.isSplit) {
                val group = requireNotNull(packet.group)
                val packets = packetsByGroup.getOrPut(group) { emptyList() }!! + packet
                if (packets.size != packet.size) {
                    log.v { "receiver: split group=$group, got ${packets.size}/${packet.size}, waiting for more" }
                    packetsByGroup.put(group, packets)
                    continue
                }

                val orderedPackets = packets.filter { it.index != null }.sortedBy { it.index!! }
                if (orderedPackets.map { it.index!! }.run { isContinuous() && first() == 0 }) {
                    log.v { "receiver: split group=$group, all ${packets.size} packets received and continuous, merging" }
                    packetsByGroup.remove(group)
                    orderedPackets.merge()
                } else {
                    log.w { "receiver: split group=$group, packets not yet continuous, caching" }
                    packetsByGroup.put(group, packets)
                    continue
                }
            } else {
                packet
            }
            val event = mergedPacket.data.readContent(MilkyConsoleFromEvent.FromHost.serializer())
            log.v { "receiver: posting event of type=${event::class.simpleName} to EventBus" }
            EventBus.post(event)
        }
        log.i { "<<< receiver coroutine exit" }
    }
    log.d { "[group: coroutine-start] receiver launched" }

    suspend fun rejectHandshake(message: String) {
        EventBus.post(PluginHandshakeResult.Rejected(message))
        handshakeRejectionSent.await()
        sink.close()
        source.close()
        exit(1)
    }

    // -------------------- 5. 握手 --------------------
    log.i { "[step 5/7] handshake phase: awaiting HostHandshakeRequest" }
    val handshakeRequest = async(start = CoroutineStart.UNDISPATCHED) {
        log.i { ">>> handshakeRequest async enter" }
        val result = withTimeoutOrNull(10.seconds) {
            log.v { "handshakeRequest: blocking until HostHandshakeRequest received (timeout=10s)" }
            EventBus.subscribe<HostHandshakeRequest>().first()
            log.v { "handshakeRequest: HostHandshakeRequest received" }
            true
        }
        if (result == null) {
            log.w { "handshakeRequest: timed out waiting for HostHandshakeRequest" }
        }
        log.d { "[group: handshake-wait] result=${result}, expected=true, match=${result == true}" }
        log.i { "<<< handshakeRequest async exit, result=$result" }
        result ?: false
    }

    if (!handshakeRequest.await()) {
        log.w { "Handshake rejected: did not receive HostHandshakeRequest within 10s" }
        rejectHandshake("10秒内没有收到握手包，取消握手")
        log.i { "<<< loader::main() exit (handshake timeout)" }
        return@runBlocking
    }
    log.i { "Handshake request received, continuing with plugin loading" }

    // -------------------- 6. 加载插件动态库 --------------------
    log.i { "[step 6/7] loading plugin dynamic library: ${args[2]}" }
    val loader = DLLoader(args[2])
    log.d { "[group: dlloader-init] DLLoader created for path=${args[2]}" }
    val api = try {
        log.v { "calling milky_plugin_get_api(MILKY_CONSOLE_HOST_ABI_VERSION=$MILKY_CONSOLE_HOST_ABI_VERSION)" }
        val result = loader.findSymbol<(UInt) -> CPointer<milky_console_plugin_api>?>("milky_plugin_get_api")
            .invoke(MILKY_CONSOLE_HOST_ABI_VERSION)
        if (result == null || result.rawValue == NativePtr.NULL) {
            log.e { "milky_plugin_get_api returned null pointer" }
            error("插件加载函数返回值为空指针")
        }
        log.v { "milky_plugin_get_api returned non-null pointer" }
        result.pointed
    } catch (e: Exception) {
        log.e { "Failed to find plugin entry function: ${e.message}" }
        rejectHandshake("无法查找到插件加载函数: ${e.message}")
        log.i { "<<< loader::main() exit (plugin symbol lookup failed)" }
        return@runBlocking
    }
    log.d { "[group: plugin-api] plugin API obtained: abi_version=${api.abi_version}, struct_size=${api.struct_size}" }

    if (api.abi_version != MILKY_CONSOLE_HOST_ABI_VERSION) {
        log.e { "plugin ABI mismatch: expected=$MILKY_CONSOLE_HOST_ABI_VERSION, actual=${api.abi_version}" }
        rejectHandshake("插件ABI不匹配。本加载器期望: ${MILKY_CONSOLE_HOST_ABI_VERSION}，该插件的ABI为${api.abi_version}")
        log.i { "<<< loader::main() exit (ABI mismatch)" }
        return@runBlocking
    }
    log.v { "ABI version check passed (expected=$MILKY_CONSOLE_HOST_ABI_VERSION)" }

    if (api.struct_size < sizeOf<milky_console_plugin_api>().toUInt()) {
        log.e { "plugin struct_size too small: expected >= ${sizeOf<milky_console_plugin_api>()}, got ${api.struct_size}" }
        rejectHandshake("插件API不匹配。")
        log.i { "<<< loader::main() exit (struct_size mismatch)" }
        return@runBlocking
    }
    log.v { "struct_size check passed (expected <= ${sizeOf<milky_console_plugin_api>()})" }

    if (api.on_load == null) {
        log.e { "plugin API does not provide on_load" }
        rejectHandshake("插件API缺少 on_load")
        log.i { "<<< loader::main() exit (on_load missing)" }
        return@runBlocking
    }

    //构造api
    log.i { "allocating hostApi and setting up send_message callback" }
    val hostApi = malloc(sizeOf<milky_console_host_api>().toULong())!!.reinterpret<milky_console_host_api>()
    hostApi.pointed.abi_version = MILKY_CONSOLE_HOST_ABI_VERSION
    hostApi.pointed.struct_size = sizeOf<milky_console_host_api>().toUInt()
    hostApi.pointed.send_message = staticCFunction { message ->
        val messageStr = message?.toKString() ?: return@staticCFunction MILKY_RESULT_INVALID_ARGUMENT
        log.v { "send_message callback invoked, message=$messageStr" }
        val event = try {
            PluginEvent(milkyJsonModule.decodeFromString(messageStr))
        } catch (_: Exception) {
            log.w { "send_message: failed to deserialize message, returning INVALID_ARGUMENT" }
            return@staticCFunction MILKY_RESULT_INVALID_ARGUMENT
        }
        val result = EventBus.tryPost(event)
        log.d { "[group: send-message] event posted to EventBus: success=$result" }
        if (result) MILKY_RESULT_OK else MILKY_RESULT_INTERNAL_ERROR
    }
    log.d { "[group: host-api] hostApi configured: abi_version=$MILKY_CONSOLE_HOST_ABI_VERSION, struct_size=${sizeOf<milky_console_host_api>().toUInt()}" }

    log.i { "calling plugin on_load(config, hostApi)" }
    val onLoadResult = memScoped {
        api.on_load!!.invoke(config.cstr.getPointer(this), hostApi)
    }
    log.v { "on_load returned: $onLoadResult (expected $MILKY_TRUE)" }
    log.d { "[group: on-load] result=$onLoadResult, expected=$MILKY_TRUE, match=${onLoadResult == MILKY_TRUE}" }

    if (onLoadResult == MILKY_FALSE) {
        log.e { "plugin on_load returned MILKY_FALSE, initialization failed" }
        rejectHandshake("插件初始化失败")
        log.i { "<<< loader::main() exit (on_load failed)" }
        return@runBlocking
    }
    log.i { "plugin on_load succeeded" }

    // -------------------- 7. 消息循环 --------------------
    log.i { "[step 7/7] entering message loop, posting PluginHandshakeResult.Ready" }
    var handshakeSuccess = false
    var closedByHost = false
    val ex = try {
        coroutineScope {
            launch(start = CoroutineStart.UNDISPATCHED) {
                log.i { ">>> message-loop: HostEvent subscriber enter" }
                EventBus.subscribe<HostEvent>().collect { message ->
                    log.v { "HostEvent received, forwarding to plugin on_message" }
                    memScoped {
                        api.on_message?.invoke(milkyJsonModule.encodeToString(message.event).cstr.getPointer(this))
                    }
                }
                log.i { "<<< message-loop: HostEvent subscriber exit" }
            }

            launch(start = CoroutineStart.UNDISPATCHED) {
                log.i { ">>> message-loop: HostClose subscriber enter" }
                EventBus.subscribe<HostClose>().collect { message ->
                    log.w { "HostClose received, reason=${message.reason}" }
                    closedByHost = true
                    this@coroutineScope.cancel(CancellationException(message.reason))
                }
                log.i { "<<< message-loop: HostClose subscriber exit" }
            }
            log.i { "posting PluginHandshakeResult.Ready to EventBus" }
            EventBus.post(PluginHandshakeResult.Ready)
            handshakeSuccess = true
            log.i { "plugin fully loaded and handshaken, message loop running" }
        }
        null
    } catch (e: Throwable) {
        log.w { "coroutineScope cancelled: ${e.message}" }
        e
    }

    if (handshakeSuccess && !closedByHost) {
        val error = ex?.cause ?: ex
        val closeMsg = error?.message ?: "插件客户端已关闭"
        log.i { "plugin closed (not by host), posting PluginClosed: $closeMsg" }
        EventBus.post(PluginClosed(closeMsg, error?.stackTraceToString()))
        yield()
    } else if (handshakeSuccess) {
        log.i { "plugin closed by host, skipping PluginClosed post" }
    }

    log.i { "joining sender and receiver coroutines" }
    sender.join()
    receiver.join()

    //结束前销毁程序
    var code: Int
    try {
        log.i { "calling plugin on_unload" }
        code = api.on_unload?.invoke() ?: 0
        log.d { "[group: cleanup] on_unload returned code=$code" }
    } finally {
        log.v { "freeing hostApi, closing pipes and loader" }
        free(hostApi)
        sink.close()
        source.close()
        loader.close()
        log.d { "[group: cleanup] all resources released" }
    }

    log.i { "<<< loader::main() exit, exitCode=$code" }
    exit(code)
}
