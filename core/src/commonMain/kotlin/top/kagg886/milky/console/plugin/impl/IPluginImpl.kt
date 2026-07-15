package top.kagg886.milky.console.plugin.impl

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import okio.Path
import top.kagg886.milky.console.plugin.IPlugin
import top.kagg886.milky.console.plugin.config.ManifestMetadata
import top.kagg886.milky.console.plugin.config.PluginManifest
import kotlin.properties.Delegates

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/15 17:12
 * ================================================
 */

class IPluginImpl(override val basePath: Path) : IPlugin {
    val manifestPath by lazy {
        basePath / "manifest.json"
    }

    val defaultConfigPath by lazy {
        basePath / "default-config.json"
    }

    val platformPath by lazy {
        basePath / "platform"
    }


    val _state = MutableStateFlow<IPlugin.State>(IPlugin.State.UnInitialized)
    override val state: StateFlow<IPlugin.State> = _state.asStateFlow()
}
