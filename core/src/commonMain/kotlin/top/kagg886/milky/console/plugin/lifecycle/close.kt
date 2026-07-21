package top.kagg886.milky.console.plugin.lifecycle

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import top.kagg886.milky.console.plugin.Plugin
import top.kagg886.milky.console.plugin.exception.PluginCloseReason
import top.kagg886.milky.console.plugin.exception.PluginhandshakeFailedException
import top.kagg886.milky.console.plugin.PluginRegistry
import top.kagg886.milky.console.util.pipe.IPCAnonymousPipeSink
import top.kagg886.milky.console.util.pipe.IPCAnonymousPipeSource
import top.kagg886.milky.console.util.process.Process

private val pluginLifecycleLogger = Logger.withTag("PluginLifecycle")
internal data class PluginRuntime(
    val process: Process,
    val processExit: Deferred<Process.ExitStatus>,
    val sendPipeJob: Job,
    val receivePipeJob: Job,
    val send: IPCAnonymousPipeSink,
    val receive: IPCAnonymousPipeSource,
)

internal suspend fun Plugin.closeHandshake(
    registry: PluginRegistry,
    runtime: PluginRuntime,
    cause: PluginhandshakeFailedException,
): Boolean {
    pluginLifecycleLogger.i { "enter closeHandshake: plugin=${basePath}, state=${state.value}, cause=${cause.message}" }
    _state.value = Plugin.State.Closing
    runtime.sendPipeJob.cancel()
    runtime.receivePipeJob.cancel()
    runCatching { runtime.send.close() }
    runCatching { runtime.receive.close() }
    if (!runtime.processExit.isCompleted) runtime.process.kill()
    runCatching { runtime.processExit.await() }
    registry.remove(this)
    _state.value = Plugin.State.Closed(
        PluginCloseReason.HandshakeFailed(cause),
    )
    pluginLifecycleLogger.i { "exit closeHandshake successfully: state=${state.value}, closedCause=${state.value}" }
    return false
}
