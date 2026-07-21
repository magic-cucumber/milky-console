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

private val logger = Logger.withTag("PluginLifecycle")
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
    logger.i { "enter closeHandshake: plugin=${basePath}, state=${state.value}, cause=${cause.message}" }
    _state.value = Plugin.State.Closing
    logger.d { "handshake close state set: plugin=${basePath}, expectedClosing=${state.value is Plugin.State.Closing}" }
    runtime.sendPipeJob.cancel()
    runtime.receivePipeJob.cancel()
    logger.v { "runtime pipe jobs cancelled: plugin=${basePath}" }
    runCatching { runtime.send.close() }
        .onFailure { logger.w(it) { "send pipe close failed during handshake cleanup: plugin=${basePath}" } }
    runCatching { runtime.receive.close() }
        .onFailure { logger.w(it) { "receive pipe close failed during handshake cleanup: plugin=${basePath}" } }
    if (!runtime.processExit.isCompleted) {
        logger.w { "handshake cleanup killing unfinished process: plugin=${basePath}" }
        runtime.process.kill()
    } else {
        logger.v { "handshake cleanup process already exited: plugin=${basePath}" }
    }
    runCatching { runtime.processExit.await() }
        .onSuccess { logger.d { "handshake cleanup process exit observed: plugin=${basePath}, status=$it" } }
        .onFailure { logger.w(it) { "handshake cleanup process await failed: plugin=${basePath}" } }
    registry.remove(this)
    _state.value = Plugin.State.Closed(
        PluginCloseReason.HandshakeFailed(cause),
    )
    logger.i { "exit closeHandshake successfully: state=${state.value}, closedCause=${state.value}" }
    return false
}
