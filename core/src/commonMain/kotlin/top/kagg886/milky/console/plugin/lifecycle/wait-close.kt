package top.kagg886.milky.console.plugin.lifecycle

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

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/16 13:21
 * ================================================
 */

fun Plugin.waitCloseJob(registry: PluginRegistry): Job =
    registry.scope.launch(start = CoroutineStart.LAZY) {
        // 任一关闭事件或 loader 自行退出都会开始关闭流程。
        // 进程退出作为 race 的一支，避免插件异常退出后一直停留在 Ready。
        val exited = raceN(
            {
                EventBus
                    .subscribe<PluginInboundEvent>()
                    .first { it.pluginId == manifest.id && it.event is PluginClosed }
                null
            },
            {
                EventBus.subscribe<Pair<String, MilkyConsoleFromEvent.FromHost>>()
                    .first { (id, event) -> id == manifest.id && event is HostClose }
                null
            },
            { process.await() },
        )
        _state.value = Plugin.State.Closing
        val status = exited ?: process.await()
        _state.value = when (status) {
            Process.ExitStatus.Killed -> Plugin.State.Closed(IOException("process killed"))
            is Process.ExitStatus.Result if status.exitCode == 0 -> Plugin.State.Closed()
            is Process.ExitStatus.Result if status.exitCode != 0 -> Plugin.State.Closed(IOException("process exited with exit code ${status.exitCode}"))
            else -> error("process exitCode $status.exitCode")
        }
    }
