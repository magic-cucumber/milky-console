package top.kagg886.milky.console.plugin

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import okio.Path
import top.kagg886.milky.console.plugin.config.PluginManifest
import top.kagg886.milky.console.util.process.Process

private val log = Logger.withTag("Plugin")

class Plugin(val basePath: Path) {
    val _state = MutableStateFlow<State>(State.UnInitialized)
    val state: StateFlow<State> = _state.asStateFlow()

    val manifestPath by lazy {
        log.v { "lazy init manifestPath: ${basePath / "manifest.json"}" }
        basePath / "manifest.json"
    }

    val defaultConfigPath by lazy {
        log.v { "lazy init defaultConfigPath: ${basePath / "default-config.json"}" }
        basePath / "default-config.json"
    }

    val platformPath by lazy {
        log.v { "lazy init platformPath: ${basePath / "platform"}" }
        basePath / "platform"
    }

    init {
        log.i { ">>> Plugin(basePath=$basePath) init enter" }
        log.d { "[group: plugin-init] basePath=$basePath, initial state=UnInitialized" }
        log.i { "<<< Plugin(basePath=$basePath) init exit" }
    }


    sealed interface State {
        //刚刚实例化插件时的状态
        data object UnInitialized : State

        //插件完整性通过
        data class Verified(
            override val libpath: Path,
            override val manifest: PluginManifest,
            override val config: JsonObject
        ) :
            State, ManifestInitialized, ConfigInitialized

        //插件正在进行握手
        data class Handshaking(
            override val libpath: Path,
            override val manifest: PluginManifest,
            override val config: JsonObject
        ) :
            State, ManifestInitialized, ConfigInitialized

        //插件已准备好通信，在这里暴露EventChannel用于通信
        data class Ready(
            override val libpath: Path,
            override val manifest: PluginManifest,
            override val config: JsonObject,
            override val process: Process,
            override val receivePipeJob: Job,
            override val sendPipeJob: Job,
            override val closeAwaitJob: Job
        ) : State, ManifestInitialized, ConfigInitialized, ProgressInitialized

        //插件因为各种原因正在关闭
        data object Closing : State

        //插件由于正常关闭/管道损坏/以及其他未知原因而关闭
        data class Closed(val exception: Throwable? = null) : State
        interface ManifestInitialized {
            val manifest: PluginManifest
            val libpath: Path
        }

        interface ConfigInitialized {
            val config: JsonObject
        }

        interface ProgressInitialized {
            val process: Process
            val sendPipeJob: Job
            val receivePipeJob: Job

            val closeAwaitJob: Job
        }
    }
}
