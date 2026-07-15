package top.kagg886.milky.console.plugin

import top.kagg886.milky.console.plugin.config.ManifestMetadata
import top.kagg886.milky.console.plugin.config.PluginManifest

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/15 18:03
 * ================================================
 */

val IPlugin.manifest: PluginManifest
    get() {
        val s = state
        check(s is IPlugin.State.ManifestInitialized) {
            "Plugin's state is not in ManifestInitialized"
        }

        return s.config
    }
