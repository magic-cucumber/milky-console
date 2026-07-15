package top.kagg886.milky.console.plugin

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import okio.Path
import top.kagg886.milky.console.plugin.config.PluginManifest

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/15 16:33
 * ================================================
 */
interface IPlugin {
    val basePath: Path
    val state: StateFlow<State>

    sealed interface State {
        //刚刚实例化插件时的状态
        data object UnInitialized : State

        //插件完整性通过
        data class Verified(val libpath: Path, override val config: PluginManifest) : State, ManifestInitialized

        //插件正在进行握手
        data object Handshaking : State

        //插件已准备好通信，在这里暴露EventChannel用于通信
        data class Ready(val data: Int) : State

        //插件因为各种原因正在关闭
        data object Closing : State

        //插件由于正常关闭/管道损坏/以及其他未知原因而关闭
        data class Closed(val exception: Throwable? = null) : State


        interface ManifestInitialized {
            val config: PluginManifest
        }
    }

}
