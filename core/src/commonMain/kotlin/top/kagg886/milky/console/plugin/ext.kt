package top.kagg886.milky.console.plugin

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonObject
import okio.Path
import top.kagg886.milky.console.plugin.config.PluginManifest
import top.kagg886.milky.console.util.process.Process

private val log = Logger.withTag("PluginExt")

val Plugin.manifest: PluginManifest
    get() {
        log.i { ">>> Plugin.manifest getter enter" }
        val s = state.value
        log.v { "manifest: current state=${s::class.simpleName}" }
        check(s is Plugin.State.ManifestInitialized) {
            log.e { "manifest: state is not ManifestInitialized, actual=${s::class.simpleName}" }
            "Plugin's state is not in ManifestInitialized"
        }
        log.d { "[group: state-access] manifest accessed, state=${s::class.simpleName}, id=${s.manifest.id}" }
        log.i { "<<< Plugin.manifest getter exit" }
        return s.manifest
    }

val Plugin.libpath: Path
    get() {
        log.i { ">>> Plugin.libpath getter enter" }
        val s = state.value
        log.v { "libpath: current state=${s::class.simpleName}" }
        check(s is Plugin.State.ManifestInitialized) {
            log.e { "libpath: state is not ManifestInitialized, actual=${s::class.simpleName}" }
            "Plugin's state is not in ManifestInitialized"
        }
        log.d { "[group: state-access] libpath accessed, state=${s::class.simpleName}, path=${s.libpath}" }
        log.i { "<<< Plugin.libpath getter exit" }
        return s.libpath
    }

val Plugin.config: JsonObject
    get() {
        log.i { ">>> Plugin.config getter enter" }
        val s = state.value
        log.v { "config: current state=${s::class.simpleName}" }
        check(s is Plugin.State.ConfigInitialized) {
            log.e { "config: state is not ConfigInitialized, actual=${s::class.simpleName}" }
            "Plugin's state is not in ConfigInitialized"
        }
        log.d { "[group: state-access] config accessed, state=${s::class.simpleName}" }
        log.i { "<<< Plugin.config getter exit" }
        return s.config
    }

val Plugin.process: Process
    get() {
        log.i { ">>> Plugin.process getter enter" }
        val s = state.value
        log.v { "process: current state=${s::class.simpleName}" }
        check(s is Plugin.State.ProgressInitialized) {
            log.e { "process: state is not ProgressInitialized, actual=${s::class.simpleName}" }
            "Plugin's state is not in ProgressInitialized"
        }
        log.d { "[group: state-access] process accessed, state=${s::class.simpleName}" }
        log.i { "<<< Plugin.process getter exit" }
        return s.process
    }

val Plugin.awaitJob: Job
    get() {
        log.i { ">>> Plugin.awaitJob getter enter" }
        val s = state.value
        log.v { "awaitJob: current state=${s::class.simpleName}" }
        check(s is Plugin.State.Ready) {
            log.e { "awaitJob: state is not Ready, actual=${s::class.simpleName}" }
            "Plugin's state is not in Ready"
        }
        log.d { "[group: state-access] awaitJob accessed, state=${s::class.simpleName}" }
        log.i { "<<< Plugin.awaitJob getter exit" }
        return s.closeAwaitJob
    }
