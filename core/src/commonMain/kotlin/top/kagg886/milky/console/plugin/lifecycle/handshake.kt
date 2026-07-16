package top.kagg886.milky.console.plugin.lifecycle

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.ExperimentalSerializationApi
import top.kagg886.milky.console.plugin.Plugin
import top.kagg886.milky.console.plugin.PluginRegistry

@OptIn(ExperimentalForeignApi::class, ExperimentalSerializationApi::class)
suspend fun Plugin.handshake(registry: PluginRegistry): Boolean {
    return true
}
