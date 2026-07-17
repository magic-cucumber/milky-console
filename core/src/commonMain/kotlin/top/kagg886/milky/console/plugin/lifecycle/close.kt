package top.kagg886.milky.console.plugin.lifecycle

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import okio.IOException
import top.kagg886.milky.console.plugin.Plugin
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
    cause: Throwable,
): Boolean {
    pluginLifecycleLogger.i { "enter closeHandshake: plugin=${basePath}, state=${state.value}, cause=${cause.message}" }
    _state.value = Plugin.State.Closing
    runtime.sendPipeJob.cancel()
    runtime.receivePipeJob.cancel()
    runCatching { runtime.send.close() }
    runCatching { runtime.receive.close() }
    if (!runtime.processExit.isCompleted) runtime.process.kill()
    runCatching { runtime.processExit.await() }
    _state.value = Plugin.State.Closed(cause)
    registry.remove(this)
    pluginLifecycleLogger.i { "exit closeHandshake successfully: state=${state.value}, closedCause=${state.value}" }
    return false
}

internal fun Process.ExitStatus.toClosedState(): Plugin.State.Closed {
    pluginLifecycleLogger.i { "enter toClosedState: status=$this" }
    val result = when (this) {
        Process.ExitStatus.Killed -> {
            pluginLifecycleLogger.w { "process was killed" }
            Plugin.State.Closed(IOException("process killed"))
        }
        is Process.ExitStatus.Result -> if (exitCode == 0) {
            pluginLifecycleLogger.d { "process exited successfully: code=0" }
            Plugin.State.Closed()
        } else {
            pluginLifecycleLogger.e { "process exited with failure: code=$exitCode" }
            Plugin.State.Closed(IOException("process exited with exit code $exitCode"))
        }
    }
    pluginLifecycleLogger.i { "exit toClosedState: result=$result" }
    return result
}
