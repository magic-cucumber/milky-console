package top.kagg886.milky.console.plugin.lifecycle

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.kagg886.milky.console.plugin.Plugin
import top.kagg886.milky.console.plugin.exception.PluginCloseReason
import top.kagg886.milky.console.plugin.PluginRegistry
import top.kagg886.milky.console.protocol.HostClose
import top.kagg886.milky.console.protocol.PluginClosed
import top.kagg886.milky.console.util.eventbus.EventBus
import top.kagg886.milky.console.util.raceN

private val logger = Logger.withTag("PluginLifecycle")

private sealed interface CloseSignal {
    data class Plugin(val event: PluginClosed) : CloseSignal
    data class Host(val event: HostClose) : CloseSignal
    data class Process(val status: top.kagg886.milky.console.util.process.Process.ExitStatus) : CloseSignal
}
internal fun Plugin.enterReady(registry: PluginRegistry, runtime: PluginRuntime): Boolean {
    logger.i { "enter enterReady: state=${state.value}" }
    val handshaking = state.value as Plugin.State.Handshaking
    val pluginId = handshaking.manifest.id

    // Register both EventBus listeners synchronously before publishing Ready state.
    val pluginClosed = registry.scope.async(start = CoroutineStart.UNDISPATCHED) {
        logger.v { "enter plugin close listener: plugin=$pluginId" }
        EventBus.subscribe<PluginInboundEvent>()
            .first { it.pluginId == pluginId && it.event is PluginClosed }
            .event as PluginClosed
    }
    val hostClose = registry.scope.async(start = CoroutineStart.UNDISPATCHED) {
        logger.v { "enter host close listener: plugin=$pluginId" }
        EventBus.subscribe<PluginOutboundEvent>()
            .first { it.pluginId == pluginId && it.event is HostClose }
            .event as HostClose
    }
    logger.d { "close listeners registered: plugin=$pluginId" }

    val closeAwaitJob = registry.scope.launch(start = CoroutineStart.LAZY) {
        logger.v { "enter close watcher: plugin=$pluginId" }
        val signal = try {
            raceN(
                { CloseSignal.Plugin(pluginClosed.await()) },
                { CloseSignal.Host(hostClose.await()) },
                { CloseSignal.Process(runtime.processExit.await()) },
            )
        } finally {
            pluginClosed.cancel()
            hostClose.cancel()
            logger.v { "close listeners cancelled: plugin=$pluginId" }
        }

        logger.i { "close signal received: plugin=$pluginId, signal=$signal" }

        _state.value = Plugin.State.Closing
        val status = when (signal) {
            is CloseSignal.Process -> {
                logger.v { "close signal branch: process exit, plugin=$pluginId" }
                signal.status
            }
            is CloseSignal.Plugin, is CloseSignal.Host -> {
                logger.v { "close signal branch: explicit close, plugin=$pluginId" }
                runtime.processExit.await()
            }
        }
        logger.d { "close status resolved: plugin=$pluginId, status=$status" }
        runtime.sendPipeJob.cancel()
        runtime.receivePipeJob.cancel()
        runCatching { runtime.send.close() }
        runCatching { runtime.receive.close() }
        registry.remove(this@enterReady)
        _state.value = when (signal) {
            is CloseSignal.Plugin -> {
                logger.w { "plugin requested close: plugin=$pluginId, message=${signal.event.message}" }
                Plugin.State.Closed(
                    PluginCloseReason.PluginRequested(signal.event.message, signal.event.stacktrace, status),
                )
            }
            is CloseSignal.Host -> {
                logger.i { "host requested plugin close: plugin=$pluginId, reason=${signal.event.reason}" }
                Plugin.State.Closed(
                    PluginCloseReason.HostRequested(signal.event.reason, status),
                )
            }
            is CloseSignal.Process -> {
                logger.w { "plugin process exited before explicit close: plugin=$pluginId, status=$status" }
                Plugin.State.Closed(PluginCloseReason.ProcessExited(status))
            }
        }
        logger.i { "close watcher exit: plugin=$pluginId, state=${state.value}" }
    }

    _state.value = Plugin.State.Ready(
        handshaking.libpath,
        handshaking.manifest,
        handshaking.config,
        runtime.process,
        runtime.receivePipeJob,
        runtime.sendPipeJob,
        closeAwaitJob,
    )
    closeAwaitJob.start()
    logger.i { "exit enterReady successfully: plugin=$pluginId, state=${state.value}" }
    return true
}

