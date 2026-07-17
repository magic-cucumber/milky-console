package top.kagg886.milky.console.plugin

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import okio.Path
import top.kagg886.milky.console.plugin.config.PluginManifest
import top.kagg886.milky.console.plugin.lifecycle.PluginInboundEvent
import top.kagg886.milky.console.plugin.lifecycle.PluginOutboundEvent
import top.kagg886.milky.console.protocol.MilkyConsoleFromEvent
import top.kagg886.milky.console.util.eventbus.EventBus
import top.kagg886.milky.console.util.process.Process
import top.kagg886.milky.console.util.raceN



val Plugin.manifest: PluginManifest
    get() {
        
        val s = state.value
        
        check(s is Plugin.State.ManifestInitialized) {
            
            "Plugin's state is not in ManifestInitialized"
        }
        
        
        return s.manifest
    }

val Plugin.libpath: Path
    get() {
        
        val s = state.value
        
        check(s is Plugin.State.ManifestInitialized) {
            
            "Plugin's state is not in ManifestInitialized"
        }
        
        
        return s.libpath
    }

val Plugin.config: JsonObject
    get() {
        
        val s = state.value
        
        check(s is Plugin.State.ConfigInitialized) {
            
            "Plugin's state is not in ConfigInitialized"
        }
        
        
        return s.config
    }

val Plugin.process: Process
    get() {
        
        val s = state.value
        
        check(s is Plugin.State.ProgressInitialized) {
            
            "Plugin's state is not in ProgressInitialized"
        }
        
        
        return s.process
    }

val Plugin.awaitJob: Job
    get() {
        
        val s = state.value
        
        check(s is Plugin.State.Ready) {
            
            "Plugin's state is not in Ready"
        }
        
        
        return s.closeAwaitJob
    }

suspend fun Plugin.send(event: MilkyConsoleFromEvent.FromHost) {
    check(state.value is Plugin.State.Ready) {
        "Plugin's state is not in Ready"
    }
    EventBus.post(PluginOutboundEvent(manifest.id, event))
}

suspend fun Plugin.nextEvent(): MilkyConsoleFromEvent.FromPlugin {
    val pluginId = manifest.id
    val flow1 = state
    val flow2 = EventBus
        .subscribe<PluginInboundEvent>()
        .filter { it.pluginId == pluginId }
        .map { it.event }

    return raceN(
        { flow2.first() },
        {
            flow1.first { it !is Plugin.State.Ready }
            throw CancellationException("Plugin is no longer ready")
        },
    )
}
