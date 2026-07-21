package top.kagg886.milky.console.plugin.lifecycle

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okio.FileSystem
import top.kagg886.milky.console.plugin.Plugin
import top.kagg886.milky.console.plugin.exception.PluginhandshakeFailedException
import top.kagg886.milky.console.plugin.PluginRegistry
import top.kagg886.milky.console.plugin.exception.PluginCloseReason
import top.kagg886.milky.console.plugin.manifest
import top.kagg886.milky.console.protocol.HostHandshakeRequest
import top.kagg886.milky.console.protocol.MilkyConsoleFromEvent
import top.kagg886.milky.console.protocol.PluginHandshakeRequest
import top.kagg886.milky.console.protocol.PluginHandshakeError
import top.kagg886.milky.console.protocol.PluginHandshakeResult
import top.kagg886.milky.console.protocol.PluginLog
import top.kagg886.milky.console.util.eventbus.EventBus
import top.kagg886.milky.console.util.eventbus.LRUCache
import top.kagg886.milky.console.util.logger.log
import top.kagg886.milky.console.util.pipe.IPCAnonymousPipe
import top.kagg886.milky.console.util.pipe.create
import top.kagg886.milky.console.util.process.Process
import top.kagg886.milky.console.util.process.ProcessConfig
import top.kagg886.milky.console.util.process.create
import top.kagg886.milky.console.util.protocol.Packet
import top.kagg886.milky.console.util.protocol.isSplit
import top.kagg886.milky.console.util.protocol.merge
import top.kagg886.milky.console.util.protocol.readPacket
import top.kagg886.milky.console.util.protocol.toPacket
import top.kagg886.milky.console.util.protocol.writePacket
import top.kagg886.milky.console.util.raceN
import top.kagg886.milky.console.util.readContent
import kotlin.experimental.ExperimentalNativeApi
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val logger = Logger.withTag("PluginHandshake")

@OptIn(
    ExperimentalForeignApi::class,
    ExperimentalSerializationApi::class,
    DelicateCoroutinesApi::class,
    ExperimentalCoroutinesApi::class, ExperimentalNativeApi::class,
)
suspend fun Plugin.handshake(registry: PluginRegistry): Boolean {
    val pluginId = manifest.id
    val pluginName = manifest.name
    logger.i { "enter handshake: id=$pluginId, state=${state.value}" }
    val verified = state.value as Plugin.State.Verified
    val sendPipe = IPCAnonymousPipe.create()
    val receivePipe = IPCAnonymousPipe.create()
    val logPipe = IPCAnonymousPipe.create()
    logger.d { "business and log pipes created: id=$pluginId" }
    // EventBus intentionally does not replay events.  Keep terminal handshake
    // state locally as well, so a pipe reader cannot publish a request/result
    // before a separately scheduled EventBus collector has registered.
    val pluginHandshakeRequest = CompletableDeferred<Unit>()
    val pluginHandshakeResult = CompletableDeferred<PluginHandshakeResult>()

    // This subscription must exist before the loader can send PluginHandshakeRequest.
    val sendPipeDispatcher = newSingleThreadContext("plugin-$pluginId-pipe-writer")
    val receivePipeDispatcher = newSingleThreadContext("plugin-$pluginId-pipe-reader")
    val sendPipeSubscribed = CompletableDeferred<Unit>()
    val sendPipeJob = registry.scope.launch(sendPipeDispatcher, start = CoroutineStart.UNDISPATCHED) {
        logger.v { "enter send pipe loop: id=$pluginId" }
        EventBus.subscribe<PluginOutboundEvent> { sendPipeSubscribed.complete(Unit) }
            .filter { it.pluginId == pluginId }
            .collect { (_, event) ->
                logger.v { "writing outbound event to loader: id=$pluginId, type=${event::class.simpleName}" }
                event.toPacket().forEach(sendPipe.sink::writePacket)
                logger.d { "outbound event written to loader: id=$pluginId, type=${event::class.simpleName}" }
            }
    }
    sendPipeJob.invokeOnCompletion { sendPipeDispatcher.close() }
    sendPipeSubscribed.await()
    logger.d { "send pipe subscription ready: id=$pluginId" }

    val receivePipeJob = registry.scope.launch(receivePipeDispatcher) {
        logger.v { "enter receive pipe loop: id=$pluginId" }
        val packetsByGroup = LRUCache.create<Uuid, List<Packet>>(1.minutes, 16 * 1024 * 1024) { _, packets ->
            packets.sumOf { it.data.size }
        }
        while (isActive) {
            val packet = try {
                receivePipe.source.readPacket()
            } catch (e: Throwable) {
                logger.w { "receive loop exited without completing normally: id=$pluginId, state=${state.value}, reason=${e.message}" }
                // A loader that closes its output before emitting a terminal packet
                // cannot complete the handshake.  Publish through the normal
                // terminal-result channel so every handshake failure has one path.
                val result = PluginHandshakeResult.Rejected("进程意外退出。", PluginHandshakeError.PROCESS_EXITED)
                pluginHandshakeResult.complete(result)
                EventBus.postBlocking(PluginInboundEvent(pluginId, result))
                break
            }
            val merged = if (!packet.isSplit) {
                logger.v { "received complete inbound packet: id=$pluginId, bytes=${packet.data.size}" }
                packet
            } else {
                val group = requireNotNull(packet.group)
                val packets = packetsByGroup.getOrPut(group) { emptyList() }!! + packet
                if (packets.size != packet.size) {
                    logger.d { "stored split packet group=$group: received=${packets.size}/${packet.size}" }
                    packetsByGroup.put(group, packets)
                    continue
                }
                val ordered = packets.filter { it.index != null }.sortedBy { it.index }
                if (!ordered.indices.all { ordered[it].index == it }) {
                    logger.w { "split packet group=$group has incomplete indexes; waiting for more packets" }
                    packetsByGroup.put(group, packets)
                    continue
                }
                packetsByGroup.remove(group)
                logger.d { "merged split packet group=$group: parts=${ordered.size}" }
                ordered.merge()
            }
            val event = merged.data.readContent<MilkyConsoleFromEvent.FromPlugin>()
            EventBus.postBlocking(PluginInboundEvent(pluginId, event))

            when (event) {
                is PluginHandshakeRequest -> {
                    logger.i { "plugin handshake request received: id=$pluginId" }
                    pluginHandshakeRequest.complete(Unit)
                }
                is PluginHandshakeResult -> {
                    logger.i { "plugin handshake result received: id=$pluginId, result=$event" }
                    pluginHandshakeResult.complete(event)
                }
                else -> logger.v { "non-handshake inbound event: id=$pluginId, type=${event::class.simpleName}" }
            }

            logger.d { "processed inbound event: id=$pluginId, sourceTag=$pluginId, type=${event::class.simpleName}" }
        }
    }
    receivePipeJob.invokeOnCompletion { receivePipeDispatcher.close() }

    val logPipeDispatcher = newSingleThreadContext("plugin-$pluginId-log-reader")
    val logPipeJob = registry.scope.launch(logPipeDispatcher) {
        logger.v { "enter log pipe loop: id=$pluginId" }
        val packetsByGroup = LRUCache.create<Uuid, List<Packet>>(1.minutes, 16 * 1024 * 1024) { _, packets ->
            packets.sumOf { it.data.size }
        }
        while (isActive) {
            val packet = try {
                logPipe.source.readPacket()
            } catch (e: Throwable) {
                logger.v { "log pipe loop ended: id=$pluginId, reason=${e.message}" }
                break
            }
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
            val event = merged.data.readContent<PluginLog>()
            val message = buildString {
                append(event.message)
                event.stacktrace?.let { appendLine().append(it) }
            }
            logger.withTag("${pluginName}($pluginId)").log(
                Severity.entries.getOrElse(event.level) { Severity.Info },
                null,
                message,
            )
        }
        logger.v { "exit log pipe loop: id=$pluginId" }
    }
    logPipeJob.invokeOnCompletion { logPipeDispatcher.close() }

    //copy libpath to other than we can hotfix when we replace plugin
    val tmp = with(verified.libpath.name) {
        val idx = lastIndexOf(".")
        val name = substring(0, idx)
        val ext = substring(idx + 1, length)
        val libs = Platform.osFamily != OsFamily.WINDOWS

        FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "${if (libs) "lib" else ""}$name-${Uuid.random().toHexString()}.$ext"
    }
    logger.d("copy plugin impl dllib to $tmp")
    FileSystem.SYSTEM.copy(verified.libpath, tmp)
    logger.d { "plugin library copied: id=$pluginId, source=${verified.libpath}, target=$tmp" }

    val process = try {
        logger.d { "creating plugin process: id=$pluginId" }
        Process.create {
            context(registry.scope.coroutineContext)
            executable(registry.loaderPath().toString())
            stdin(ProcessConfig.IOOptions.None)
            stdout(ProcessConfig.IOOptions.None)
            stderr(ProcessConfig.IOOptions.None)
            arguments(
                receivePipe.sink.fd.toString(),
                sendPipe.source.fd.toString(),
                logPipe.sink.fd.toString(),
                tmp.toString(),
                Json.encodeToString(verified.config),
                registry.pluginDataPath(this@handshake).toString(),
                Logger.mutableConfig.minSeverity.name,
            )
            inheritFD(sendPipe.source.fd, receivePipe.sink.fd, logPipe.sink.fd)
        }
    } catch (e: Throwable) {
        logger.e { "process creation failed: id=$pluginId, message=${e.message}" }
        _state.value = Plugin.State.Closing
        pluginHandshakeRequest.cancel()
        pluginHandshakeResult.cancel()
        sendPipeJob.cancel()
        receivePipeJob.cancel()
        logPipeJob.cancel()
        sendPipe.sink.close()
        sendPipe.source.close()
        receivePipe.sink.close()
        receivePipe.source.close()
        logPipe.sink.close()
        logPipe.source.close()
        registry.remove(this)
        _state.value = Plugin.State.Closed(
            PluginCloseReason.HandshakeFailed(
                PluginhandshakeFailedException(
                    "无法启动插件进程: ${e.message}",
                    PluginHandshakeError.PROCESS_START_FAILED,
                    e,
                )
            )
        )
        logger.e { "exit handshake unsuccessfully: id=$pluginId, state=${state.value}" }
        return false
    }

    sendPipe.source.close()
    receivePipe.sink.close()
    logPipe.sink.close()
    _state.value = Plugin.State.Handshaking(verified.libpath, verified.manifest, verified.config)
    logger.d { "process started and state entered Handshaking: id=$pluginId" }

    // One process waiter is shared by handshake and close lifecycle; waitpid is never called twice.
    val processExit = registry.scope.async { process.await() }
    val runtime = PluginRuntime(process, processExit, sendPipeJob, receivePipeJob, sendPipe.sink, receivePipe.source)

    val loaderIsListening = withTimeoutOrNull(10.seconds) {
        logger.v { "waiting loader listener: id=$pluginId" }
        raceN(
            { pluginHandshakeRequest.await(); true },
            { processExit.await(); false },
        )
    } == true
    if (!loaderIsListening) {
        pluginHandshakeResult.cancel()
        val error = if (processExit.isCompleted) PluginHandshakeError.PROCESS_EXITED else PluginHandshakeError.TIMEOUT
        val message = if (processExit.isCompleted) "进程意外退出。" else "等待 loader 准备握手超时"
        logger.e { "loader did not become ready: id=$pluginId, error=$error, message=$message" }
        return closeHandshake(registry, runtime, PluginhandshakeFailedException(message, error))
    }
    logger.i { "loader is listening for host handshake: id=$pluginId" }

    // Loader has explicitly confirmed its HostHandshakeRequest listener is active.
    EventBus.post(PluginOutboundEvent(pluginId, HostHandshakeRequest))
    logger.d { "host handshake request posted: id=$pluginId" }

    val result = withTimeoutOrNull(10.seconds) {
        logger.v { "waiting plugin handshake result: id=$pluginId" }
        raceN(
            { pluginHandshakeResult.await() },
            {
                processExit.await()
                // The process can exit immediately after writing Rejected. Drain the pipe
                // before classifying it as a crash so the buffered terminal packet wins.
                receivePipeJob.join()
                if (pluginHandshakeResult.isCompleted) {
                    pluginHandshakeResult.await()
                } else {
                    PluginHandshakeResult.Rejected("进程意外退出。", PluginHandshakeError.PROCESS_EXITED)
                }
            },
        )
    } ?: PluginHandshakeResult.Rejected("握手超时", PluginHandshakeError.TIMEOUT)

    return when (result) {
        PluginHandshakeResult.Ready -> {
            logger.i { "handshake accepted: id=$pluginId" }
            val ready = enterReady(registry, runtime)
            logger.i { "exit handshake successfully: id=$pluginId, ready=$ready, state=${state.value}" }
            ready
        }

        is PluginHandshakeResult.Rejected -> closeHandshake(
            registry,
            runtime,
            PluginhandshakeFailedException(result.message, result.error),
        ).also { logger.e { "handshake rejected: id=$pluginId, message=${result.message}, error=${result.error}" } }
    }
}
