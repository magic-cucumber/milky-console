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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import top.kagg886.milky.console.plugin.Plugin
import top.kagg886.milky.console.plugin.PluginhandshakeFailedException
import top.kagg886.milky.console.plugin.PluginRegistry
import top.kagg886.milky.console.plugin.PluginCloseReason
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val pluginHandshakeLogger = Logger.withTag("PluginHandshake")

@OptIn(
    ExperimentalForeignApi::class,
    ExperimentalSerializationApi::class,
    DelicateCoroutinesApi::class,
    ExperimentalCoroutinesApi::class,
)
suspend fun Plugin.handshake(registry: PluginRegistry): Boolean {
    val pluginId = manifest.id
    pluginHandshakeLogger.i { "enter handshake: id=$pluginId, state=${state.value}" }
    val verified = state.value as Plugin.State.Verified
    val sendPipe = IPCAnonymousPipe.create()
    val receivePipe = IPCAnonymousPipe.create()

    // This subscription must exist before the loader can send PluginHandshakeRequest.
    val sendPipeDispatcher = newSingleThreadContext("plugin-$pluginId-pipe-writer")
    val receivePipeDispatcher = newSingleThreadContext("plugin-$pluginId-pipe-reader")
    val sendPipeSubscribed = CompletableDeferred<Unit>()
    val sendPipeJob = registry.scope.launch(sendPipeDispatcher, start = CoroutineStart.UNDISPATCHED) {
        EventBus.subscribe<PluginOutboundEvent> { sendPipeSubscribed.complete(Unit) }
            .filter { it.pluginId == pluginId }
            .collect { (_, event) ->
                event.toPacket().forEach(sendPipe.sink::writePacket)
            }
    }
    sendPipeJob.invokeOnCompletion { sendPipeDispatcher.close() }
    sendPipeSubscribed.await()

    val receivePipeJob = registry.scope.launch(receivePipeDispatcher) {
        val packetsByGroup = LRUCache.create<Uuid, List<Packet>>(1.minutes, 16 * 1024 * 1024) { _, packets ->
            packets.sumOf { it.data.size }
        }
        while (isActive) {
            val packet = try {
                receivePipe.source.readPacket()
            } catch (e: Throwable) {
                pluginHandshakeLogger.w { "receive loop exited without completing normally: id=$pluginId, state=${state.value}, reason=${e.message}" }
                // A loader that closes its output before emitting a terminal packet
                // cannot complete the handshake.  Publish through the normal
                // terminal-result channel so every handshake failure has one path.
                EventBus.postBlocking(
                    PluginInboundEvent(
                        pluginId,
                        PluginHandshakeResult.Rejected("进程意外退出。", PluginHandshakeError.PROCESS_EXITED),
                    ),
                )
                break
            }
            val merged = if (!packet.isSplit) {
                packet
            } else {
                val group = requireNotNull(packet.group)
                val packets = packetsByGroup.getOrPut(group) { emptyList() }!! + packet
                if (packets.size != packet.size) {
                    pluginHandshakeLogger.d { "stored split packet group=$group: received=${packets.size}/${packet.size}" }
                    packetsByGroup.put(group, packets)
                    continue
                }
                val ordered = packets.filter { it.index != null }.sortedBy { it.index }
                if (!ordered.indices.all { ordered[it].index == it }) {
                    pluginHandshakeLogger.w { "split packet group=$group has incomplete indexes; waiting for more packets" }
                    packetsByGroup.put(group, packets)
                    continue
                }
                packetsByGroup.remove(group)
                ordered.merge()
            }
            val event = merged.data.readContent<MilkyConsoleFromEvent.FromPlugin>()
            if (event is PluginLog) {
                val message = buildString {
                    append(event.message)
                    if (event.stacktrace != null) {
                        appendLine()
                        append(event.stacktrace)
                    }
                }
                pluginHandshakeLogger.withTag("Plugin($pluginId)").log(
                    Severity.entries.getOrElse(event.level) { Severity.Info },
                    null,
                    message,
                )
            }
            EventBus.postBlocking(PluginInboundEvent(pluginId, event))

            if (event !is PluginLog) {
                pluginHandshakeLogger.d { "processed inbound event: id=$pluginId, sourceTag=${if (event is PluginLog) event.tag else pluginId}, type=${event::class.simpleName}" }
            }
        }
    }
    receivePipeJob.invokeOnCompletion { receivePipeDispatcher.close() }

    // UNDISTPATCHED guarantees EventBus has registered both channels before Process.create.
    val pluginHandshakeRequest = registry.scope.async(start = CoroutineStart.UNDISPATCHED) {
        EventBus.subscribe<PluginInboundEvent>()
            .first { it.pluginId == pluginId && it.event is PluginHandshakeRequest }
    }
    val pluginHandshakeResult = registry.scope.async(start = CoroutineStart.UNDISPATCHED) {
        EventBus.subscribe<PluginInboundEvent>()
            .first { it.pluginId == pluginId && it.event is PluginHandshakeResult }
            .event as PluginHandshakeResult
    }

    val process = try {
        Process.create {
            context(registry.scope.coroutineContext)
            executable(registry.loaderPath().toString())
            stdin(ProcessConfig.IOOptions.None)
            stdout(ProcessConfig.IOOptions.None)
            stderr(ProcessConfig.IOOptions.None)
            arguments(
                receivePipe.sink.fd.toString(),
                sendPipe.source.fd.toString(),
                verified.libpath.toString(),
                Json.encodeToString(verified.config),
                registry.pluginDataPath(this@handshake).toString()
            )
            inheritFD(sendPipe.source.fd, receivePipe.sink.fd)
        }
    } catch (e: Throwable) {
        pluginHandshakeLogger.e { "process creation failed: id=$pluginId, message=${e.message}" }
        _state.value = Plugin.State.Closing
        pluginHandshakeRequest.cancel()
        pluginHandshakeResult.cancel()
        sendPipeJob.cancel()
        receivePipeJob.cancel()
        sendPipe.sink.close()
        sendPipe.source.close()
        receivePipe.sink.close()
        receivePipe.source.close()
        registry.remove(this)
        _state.value = Plugin.State.Closed(
            PluginCloseReason.HandshakeFailed(PluginhandshakeFailedException(
                "无法启动插件进程: ${e.message}",
                PluginHandshakeError.PROCESS_START_FAILED,
                e,
            ))
        )
        pluginHandshakeLogger.e { "exit handshake unsuccessfully: id=$pluginId, state=${state.value}" }
        return false
    }

    sendPipe.source.close()
    receivePipe.sink.close()
    _state.value = Plugin.State.Handshaking(verified.libpath, verified.manifest, verified.config)
    pluginHandshakeLogger.d { "process started and state entered Handshaking: id=$pluginId" }

    // One process waiter is shared by handshake and close lifecycle; waitpid is never called twice.
    val processExit = registry.scope.async { process.await() }
    val runtime = PluginRuntime(process, processExit, sendPipeJob, receivePipeJob, sendPipe.sink, receivePipe.source)

    val loaderIsListening = withTimeoutOrNull(10.seconds) {
        raceN(
            { pluginHandshakeRequest.await(); true },
            { processExit.await(); false },
        )
    } == true
    if (!loaderIsListening) {
        pluginHandshakeResult.cancel()
        val error = if (processExit.isCompleted) PluginHandshakeError.PROCESS_EXITED else PluginHandshakeError.TIMEOUT
        val message = if (processExit.isCompleted) "进程意外退出。" else "等待 loader 准备握手超时"
        pluginHandshakeLogger.e { "loader did not become ready: id=$pluginId, error=$error, message=$message" }
        return closeHandshake(registry, runtime, PluginhandshakeFailedException(message, error))
    }

    // Loader has explicitly confirmed its HostHandshakeRequest listener is active.
    EventBus.post(PluginOutboundEvent(pluginId, HostHandshakeRequest))

    val result = withTimeoutOrNull(10.seconds) {
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
            pluginHandshakeLogger.i { "handshake accepted: id=$pluginId" }
            val ready = enterReady(registry, runtime)
            pluginHandshakeLogger.i { "exit handshake successfully: id=$pluginId, ready=$ready, state=${state.value}" }
            ready
        }
        is PluginHandshakeResult.Rejected -> closeHandshake(
            registry,
            runtime,
            PluginhandshakeFailedException(result.message, result.error),
        ).also { pluginHandshakeLogger.e { "handshake rejected: id=$pluginId, message=${result.message}, error=${result.error}" } }
    }
}
