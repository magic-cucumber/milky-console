package top.kagg886.milky.console.plugin.lifecycle

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.kagg886.milky.console.plugin.Plugin
import top.kagg886.milky.console.plugin.PluginRegistry
import top.kagg886.milky.console.plugin.process
import top.kagg886.milky.console.util.process.Process

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/16 13:21
 * ================================================
 */

fun Plugin.waitCloseJob(registry: PluginRegistry, process: Process): Job =
    registry.scope.launch(start = CoroutineStart.LAZY) {
        val status = process.await()
        _state.value = Plugin.State.Closing

        _state.value = Plugin.State.Closed()
    }
