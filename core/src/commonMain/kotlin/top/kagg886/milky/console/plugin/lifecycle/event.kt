package top.kagg886.milky.console.plugin.lifecycle

import top.kagg886.milky.console.protocol.MilkyConsoleFromEvent

/**
 * ================================================
 * Author:     886kagg
 * Created on: 2026/7/16 21:44
 * ================================================
 */

data class PluginInboundEvent(
    val pluginId: String,
    val event: MilkyConsoleFromEvent.FromPlugin,
)

data class PluginOutboundEvent(
    val pluginId: String,
    val event: MilkyConsoleFromEvent.FromHost,
)
