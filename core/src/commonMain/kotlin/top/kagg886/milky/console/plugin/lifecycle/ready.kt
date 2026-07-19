package top.kagg886.milky.console.plugin.lifecycle

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import okio.IOException
import kotlinx.coroutines.launch
import top.kagg886.milky.console.plugin.Plugin
import top.kagg886.milky.console.plugin.PluginRegistry
import top.kagg886.milky.console.plugin.config
import top.kagg886.milky.console.plugin.libpath
import top.kagg886.milky.console.plugin.manifest
import top.kagg886.milky.console.protocol.HostClose
import top.kagg886.milky.console.protocol.PluginClosed
import top.kagg886.milky.console.util.eventbus.EventBus
import top.kagg886.milky.console.util.raceN

private val pluginLifecycleLogger = Logger.withTag("PluginLifecycle")

private sealed interface CloseSignal {
    data class Plugin(val event: PluginClosed) : CloseSignal
    data object Host : CloseSignal
    data class Process(val status: top.kagg886.milky.console.util.process.Process.ExitStatus) : CloseSignal
}

internal fun Plugin.enterReady(registry: PluginRegistry, runtime: PluginRuntime): Boolean {
    pluginLifecycleLogger.i { "enter enterReady: state=${state.value}" }
    val handshaking = state.value as Plugin.State.Handshaking
    val pluginId = handshaking.manifest.id

    // Register both EventBus listeners synchronously before publishing Ready state.
    val pluginClosed = registry.scope.async(start = CoroutineStart.UNDISPATCHED) {
        EventBus.subscribe<PluginInboundEvent>()
            .first { it.pluginId == pluginId && it.event is PluginClosed }
            .event as PluginClosed
    }
    val hostClose = registry.scope.async(start = CoroutineStart.UNDISPATCHED) {
        EventBus.subscribe<PluginOutboundEvent>()
            .first { it.pluginId == pluginId && it.event is HostClose }
    }

    val closeAwaitJob = registry.scope.launch(start = CoroutineStart.LAZY) {
        val signal = try {
            raceN(
                { CloseSignal.Plugin(pluginClosed.await()) },
                { hostClose.await(); CloseSignal.Host },
                { CloseSignal.Process(runtime.processExit.await()) },
            )
        } finally {
            pluginClosed.cancel()
            hostClose.cancel()
        }

        pluginLifecycleLogger.i { "close signal received: plugin=$pluginId, signal=$signal" }

        _state.value = Plugin.State.Closing
        val status = when (signal) {
            is CloseSignal.Process -> signal.status
            is CloseSignal.Plugin, CloseSignal.Host -> runtime.processExit.await()
        }
        runtime.sendPipeJob.cancel()
        runtime.receivePipeJob.cancel()
        runCatching { runtime.send.close() }
        runCatching { runtime.receive.close() }
        registry.remove(this@enterReady)
        _state.value = when (signal) {
            is CloseSignal.Plugin -> signal.event.toClosedState()
            CloseSignal.Host, is CloseSignal.Process -> status.toClosedState()
        }
        pluginLifecycleLogger.i { "close watcher exit: plugin=$pluginId, state=${state.value}" }
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
    pluginLifecycleLogger.i { "exit enterReady successfully: plugin=$pluginId, state=${state.value}" }
    return true
}

private fun PluginClosed.toClosedState(): Plugin.State.Closed {
    val detail = stacktrace?.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
    return Plugin.State.Closed(IOException("plugin closed unexpectedly: $message$detail"))
}
