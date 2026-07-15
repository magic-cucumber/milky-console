package top.kagg886.milky.console.plugin

import kotlinx.serialization.json.JsonObject
import okio.Path
import top.kagg886.milky.console.util.pipe.IPCAnonymousPipe
import top.kagg886.milky.console.util.process.Process
import top.kagg886.milky.console.plugin.config.PluginManifest
import top.kagg886.saltify.console.util.dlloader.DLLoader

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/15 18:03
 * ================================================
 */

val Plugin.manifest: PluginManifest
    get() {
        val s = state
        check(s is Plugin.State.ManifestInitialized) {
            "Plugin's state is not in ManifestInitialized"
        }

        return s.manifest
    }

val Plugin.libpath: Path
    get() {
        val s = state
        check(s is Plugin.State.ManifestInitialized) {
            "Plugin's state is not in ManifestInitialized"
        }
        return s.libpath
    }

val Plugin.config: JsonObject
    get() {
        val s = state
        check(s is Plugin.State.ConfigInitialized) {
            "Plugin's state is not in ConfigInitialized"
        }
        return s.config
    }

val Plugin.process: Process
    get() {
        val s = state
        check(s is Plugin.State.ProgressInitialized) {
            "Plugin's state is not in ProgressInitialized"
        }
        return s.process
    }

val Plugin.ipc: IPCAnonymousPipe
    get() {
        val s = state
        check(s is Plugin.State.ProgressInitialized) {
            "Plugin's state is not in ProgressInitialized"
        }
        return s.ipc
    }

val Plugin.dlLoader: DLLoader
    get() {
        val s = state
        check(s is Plugin.State.ProgressInitialized) {
            "Plugin's state is not in ProgressInitialized"
        }
        return s.dlLoader
    }
