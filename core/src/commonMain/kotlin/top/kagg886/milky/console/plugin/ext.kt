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


private val logger = Logger.withTag("PluginExt")

val Plugin.manifest: PluginManifest
    get() {
        logger.v { "enter manifest getter: plugin=$basePath, state=${state.value}" }
        val s = state.value
        check(s is Plugin.State.ManifestInitialized) {
            logger.e { "manifest getter failed: plugin=$basePath, state=$s" }
            "Plugin's state is not in ManifestInitialized"
        }
        logger.v { "exit manifest getter: plugin=$basePath, id=${s.manifest.id}" }
        return s.manifest
    }

val Plugin.libpath: Path
    get() {
        logger.v { "enter libpath getter: plugin=$basePath, state=${state.value}" }
        val s = state.value
        check(s is Plugin.State.ManifestInitialized) {
            logger.e { "libpath getter failed: plugin=$basePath, state=$s" }
            "Plugin's state is not in ManifestInitialized"
        }
        logger.v { "exit libpath getter: plugin=$basePath, libpath=${s.libpath}" }
        return s.libpath
    }

val Plugin.config: JsonObject
    get() {
        logger.v { "enter config getter: plugin=$basePath, state=${state.value}" }
        val s = state.value
        check(s is Plugin.State.ConfigInitialized) {
            logger.e { "config getter failed: plugin=$basePath, state=$s" }
            "Plugin's state is not in ConfigInitialized"
        }
        logger.v { "exit config getter: plugin=$basePath, keys=${s.config.size}" }
        return s.config
    }

val Plugin.process: Process
    get() {
        logger.v { "enter process getter: plugin=$basePath, state=${state.value}" }
        val s = state.value
        check(s is Plugin.State.ProgressInitialized) {
            logger.e { "process getter failed: plugin=$basePath, state=$s" }
            "Plugin's state is not in ProgressInitialized"
        }
        logger.v { "exit process getter: plugin=$basePath" }
        return s.process
    }

val Plugin.awaitJob: Job
    get() {
        logger.v { "enter awaitJob getter: plugin=$basePath, state=${state.value}" }
        val s = state.value
        check(s is Plugin.State.Ready) {
            logger.e { "awaitJob getter failed: plugin=$basePath, state=$s" }
            "Plugin's state is not in Ready"
        }
        logger.v { "exit awaitJob getter: plugin=$basePath" }
        return s.closeAwaitJob
    }

suspend fun Plugin.send(event: MilkyConsoleFromEvent.FromHost) {
    logger.i { "enter send: plugin=$basePath, state=${state.value}, event=${event::class.simpleName}" }
    check(state.value is Plugin.State.Ready) {
        logger.e { "send failed: plugin=$basePath is not ready, state=${state.value}" }
        "Plugin's state is not in Ready"
    }
    EventBus.post(PluginOutboundEvent(manifest.id, event))
    logger.d { "posted outbound event: plugin=${manifest.id}, type=${event::class.simpleName}, expected=true" }
    logger.i { "exit send successfully: plugin=${manifest.id}" }
}

suspend fun Plugin.nextEvent(): MilkyConsoleFromEvent.FromPlugin {
    logger.i { "enter nextEvent: plugin=$basePath, state=${state.value}" }
    val pluginId = manifest.id
    val flow1 = state
    val flow2 = EventBus
        .subscribe<PluginInboundEvent>()
        .filter { it.pluginId == pluginId }
        .map { it.event }
    logger.d { "nextEvent flows prepared: plugin=$pluginId" }

    return raceN(
        {
            logger.v { "nextEvent waiting inbound event: plugin=$pluginId" }
            flow2.first().also { logger.i { "nextEvent received inbound event: plugin=$pluginId, type=${it::class.simpleName}" } }
        },
        {
            logger.v { "nextEvent waiting non-ready state: plugin=$pluginId" }
            flow1.first { it !is Plugin.State.Ready }
            logger.w { "nextEvent exiting because plugin is no longer ready: plugin=$pluginId, state=${state.value}" }
            throw CancellationException("Plugin is no longer ready")
        },
    ).also { logger.i { "exit nextEvent successfully: plugin=$pluginId, type=${it::class.simpleName}" } }
}
