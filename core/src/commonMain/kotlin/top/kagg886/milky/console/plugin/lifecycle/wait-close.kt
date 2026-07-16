package top.kagg886.milky.console.plugin.lifecycle

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okio.IOException
import top.kagg886.milky.console.plugin.Plugin
import top.kagg886.milky.console.plugin.PluginRegistry
import top.kagg886.milky.console.plugin.manifest
import top.kagg886.milky.console.plugin.process
import top.kagg886.milky.console.protocol.HostClose
import top.kagg886.milky.console.protocol.MilkyConsoleFromEvent
import top.kagg886.milky.console.protocol.PluginClosed
import top.kagg886.milky.console.util.eventbus.EventBus
import top.kagg886.milky.console.util.process.Process
import top.kagg886.milky.console.util.raceN

private val log = Logger.withTag("WaitClose")

fun Plugin.waitCloseJob(registry: PluginRegistry): Job {
    log.i { ">>> Plugin.waitCloseJob() enter, pluginId=${manifest.id}" }
    log.d { "[group: close-job] creating LAZY coroutine for close-await" }

    return registry.scope.launch(start = CoroutineStart.LAZY) {
        log.i { ">>> waitCloseJob coroutine enter, pluginId=${manifest.id}" }
        log.v { "waitCloseJob: racing PluginClosed, HostClose, and process exit" }
        val exited = raceN(
            {
                log.v { "race: waiting for PluginClosed event from plugin" }
                EventBus
                    .subscribe<PluginInboundEvent>()
                    .first { it.pluginId == manifest.id && it.event is PluginClosed }
                log.v { "race: PluginClosed event received" }
                null
            },
            {
                log.v { "race: waiting for HostClose event from host" }
                EventBus.subscribe<Pair<String, MilkyConsoleFromEvent.FromHost>>()
                    .first { (id, event) -> id == manifest.id && event is HostClose }
                log.v { "race: HostClose event received" }
                null
            },
            {
                log.v { "race: waiting for process exit" }
                process.await()
            },
        )
        log.d { "[group: close-race] race completed, exited=$exited" }
        _state.value = Plugin.State.Closing
        log.i { "waitCloseJob: state transition to Closing" }

        val status = exited ?: process.await()
        log.v { "waitCloseJob: final status=$status" }
        _state.value = when (status) {
            Process.ExitStatus.Killed -> {
                log.w { "waitCloseJob: process killed, transition to Closed" }
                Plugin.State.Closed(IOException("process killed"))
            }
            is Process.ExitStatus.Result if status.exitCode == 0 -> {
                log.i { "waitCloseJob: process exited normally (code=0), transition to Closed" }
                Plugin.State.Closed()
            }
            is Process.ExitStatus.Result if status.exitCode != 0 -> {
                log.w { "waitCloseJob: process exited with code ${status.exitCode}, transition to Closed" }
                Plugin.State.Closed(IOException("process exited with exit code ${status.exitCode}"))
            }
            else -> {
                log.e { "waitCloseJob: unexpected exit status ${status?.let { it::class.simpleName } ?: "null"}" }
                error("unexpected exit status $status")
            }
        }
        log.d { "[group: state-transition] plugin ${manifest.id} state -> Closed" }

        registry.remove(this@waitCloseJob)
        log.v { "waitCloseJob: plugin removed from registry" }
        log.i { "<<< waitCloseJob coroutine exit, pluginId=${manifest.id}" }
    }
}
