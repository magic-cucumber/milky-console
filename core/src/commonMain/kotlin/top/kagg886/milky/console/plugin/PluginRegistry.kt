package top.kagg886.milky.console.plugin

import okio.Path
import top.kagg886.milky.console.plugin.impl.IPluginImpl
import top.kagg886.milky.console.plugin.lifecycle.verify

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/15 17:08
 * ================================================
 */
object PluginRegistry {
    //存放全部非Initialized和Closed插件
    private val _plugins = mutableSetOf<IPlugin>()
    val plugins: Set<IPlugin> = _plugins

    /**
     * 根据base-path实例化插件。
     */
    fun make(path: Path): IPlugin {
        val impl = IPluginImpl(path)
        if (impl.verify()) {
            _plugins.add(impl)
        }
        return impl
    }
}
