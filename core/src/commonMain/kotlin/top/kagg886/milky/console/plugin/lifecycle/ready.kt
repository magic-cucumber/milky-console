package top.kagg886.milky.console.plugin.lifecycle

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.kagg886.milky.console.plugin.Plugin
import top.kagg886.milky.console.plugin.PluginRegistry
import top.kagg886.milky.console.protocol.HostClose
import top.kagg886.milky.console.protocol.PluginClosed
import top.kagg886.milky.console.util.eventbus.EventBus
import top.kagg886.milky.console.util.raceN

private sealed interface CloseSignal {
    data object Plugin : CloseSignal
    data object Host : CloseSignal
    data class Process(val status: top.kagg886.milky.console.util.process.Process.ExitStatus) : CloseSignal
}

internal fun Plugin.enterReady(registry: PluginRegistry, runtime: PluginRuntime): Boolean {
    val handshaking = state.value as Plugin.State.Handshaking
    val pluginId = handshaking.manifest.id

    // Register both EventBus listeners synchronously before publishing Ready state.
    val pluginClosed = registry.scope.async(start = CoroutineStart.UNDISPATCHED) {
        EventBus.subscribe<PluginInboundEvent>()
            .first { it.pluginId == pluginId && it.event is PluginClosed }
    }
    val hostClose = registry.scope.async(start = CoroutineStart.UNDISPATCHED) {
        EventBus.subscribe<PluginOutboundEvent>()
            .first { it.pluginId == pluginId && it.event is HostClose }
    }

    val closeAwaitJob = registry.scope.launch(start = CoroutineStart.LAZY) {
        val signal = try {
            raceN(
                { pluginClosed.await(); CloseSignal.Plugin },
                { hostClose.await(); CloseSignal.Host },
                { CloseSignal.Process(runtime.processExit.await()) },
            )
        } finally {
            pluginClosed.cancel()
            hostClose.cancel()
        }

        _state.value = Plugin.State.Closing
        val status = when (signal) {
            is CloseSignal.Process -> signal.status
            CloseSignal.Plugin, CloseSignal.Host -> runtime.processExit.await()
        }
        runtime.sendPipeJob.cancel()
        runtime.receivePipeJob.cancel()
        runCatching { runtime.send.close() }
        runCatching { runtime.receive.close() }
        _state.value = status.toClosedState()
        registry.remove(this@enterReady)
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
    return true
}
