package top.kagg886.milky.console.plugin.lifecycle

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineStart
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

@OptIn(
    ExperimentalForeignApi::class,
    ExperimentalSerializationApi::class,
    DelicateCoroutinesApi::class,
    ExperimentalCoroutinesApi::class,
)
suspend fun Plugin.handshake(registry: PluginRegistry): Boolean {
    val pluginId = manifest.id
    val verified = state.value as Plugin.State.Verified
    val sendPipe = IPCAnonymousPipe.create()
    val receivePipe = IPCAnonymousPipe.create()

    // This subscription must exist before the loader can send PluginHandshakeRequest.
    val sendPipeDispatcher = newSingleThreadContext("plugin-$pluginId-pipe-writer")
    val receivePipeDispatcher = newSingleThreadContext("plugin-$pluginId-pipe-reader")
    val sendPipeJob = registry.scope.launch(sendPipeDispatcher, start = CoroutineStart.UNDISPATCHED) {
        EventBus.subscribe<PluginOutboundEvent>()
            .filter { it.pluginId == pluginId }
            .collect { (_, event) ->
                event.toPacket().forEach(sendPipe.sink::writePacket)
            }
    }
    sendPipeJob.invokeOnCompletion { sendPipeDispatcher.close() }

    val receivePipeJob = registry.scope.launch(receivePipeDispatcher) {
        val packetsByGroup = LRUCache.create<Uuid, List<Packet>>(1.minutes, 16 * 1024 * 1024) { _, packets ->
            packets.sumOf { it.data.size }
        }
        while (isActive) {
            val packet = try {
                receivePipe.source.readPacket()
            } catch (_: Throwable) {
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
            val event = merged.data.readContent<MilkyConsoleFromEvent.FromPlugin>()
            if (event is PluginLog) {
                Logger.withTag(event.tag).log(
                    Severity.entries.getOrElse(event.level) { Severity.Info },
                    null,
                    event.message,
                )
            }
            EventBus.postBlocking(PluginInboundEvent(pluginId, event))
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
        _state.value = Plugin.State.Closing
        pluginHandshakeRequest.cancel()
        pluginHandshakeResult.cancel()
        sendPipeJob.cancel()
        receivePipeJob.cancel()
        sendPipe.sink.close()
        sendPipe.source.close()
        receivePipe.sink.close()
        receivePipe.source.close()
        _state.value = Plugin.State.Closed(
            PluginhandshakeFailedException(
                "无法启动插件进程: ${e.message}",
                PluginHandshakeError.PROCESS_START_FAILED,
                e,
            )
        )
        return false
    }

    sendPipe.source.close()
    receivePipe.sink.close()
    _state.value = Plugin.State.Handshaking(verified.libpath, verified.manifest, verified.config)

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
        PluginHandshakeResult.Ready -> enterReady(registry, runtime)
        is PluginHandshakeResult.Rejected -> closeHandshake(
            registry,
            runtime,
            PluginhandshakeFailedException(result.message, result.error),
        )
    }
}
