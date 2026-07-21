package top.kagg886.milky.console.plugin.lifecycle

import co.touchlab.kermit.Logger
import top.kagg886.milky.console.protocol.MilkyConsoleFromEvent

/**
 * ================================================
 * Author:     886kagg
 * Created on: 2026/7/16 21:44
 * ================================================
 */

private val logger = Logger.withTag("PluginLifecycleEvent")

data class PluginInboundEvent(
    val pluginId: String,
    val event: MilkyConsoleFromEvent.FromPlugin,
) {
    init {
        logger.v { "created inbound event wrapper: plugin=$pluginId, type=${event::class.simpleName}" }
    }
}

data class PluginOutboundEvent(
    val pluginId: String,
    val event: MilkyConsoleFromEvent.FromHost,
) {
    init {
        logger.v { "created outbound event wrapper: plugin=$pluginId, type=${event::class.simpleName}" }
    }
}
