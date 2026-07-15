package top.kagg886.milky.console.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import top.kagg886.milky.console.plugin.lifecycle.handshake
import top.kagg886.milky.console.plugin.lifecycle.verify
import kotlin.experimental.ExperimentalNativeApi

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/15 17:08
 * ================================================
 */
class PluginRegistry(val appBasePath: Path) {
    init {
        if (appBasePath.isAbsolute.not()) {
            error("appBasePath should be absolute")
        }

        if (FileSystem.SYSTEM.metadataOrNull(appBasePath)?.isDirectory != true) {
            error("appBasePath should be a directory and exists")
        }

        FileSystem.SYSTEM.createDirectories(appBasePath / "config")
        FileSystem.SYSTEM.createDirectories(appBasePath / "container")
    }

    //存放全部非Initialized和Closed插件
    private val _plugins = mutableSetOf<Plugin>()
    private val pluginsModifyLock = Mutex()
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 根据base-path实例化插件。
     */
    suspend fun make(path: Path): Plugin {
        val impl = Plugin(path)
        if (impl.verify()) {
            pluginsModifyLock.withLock {
                _plugins.add(impl)
            }
        }

        scope.launch {
            if (!impl.handshake(this@PluginRegistry)) {
                pluginsModifyLock.withLock {
                    _plugins.remove(impl)
                }
            }
        }

        return impl
    }

    /**
     * 获取插件config的目录
     */
    fun pluginConfigPath(plugin: Plugin): Path {
        val path = appBasePath / "config" / "${plugin.manifest.id}.json"
        if (!FileSystem.SYSTEM.exists(path)) {
            FileSystem.SYSTEM.write(path) {
                writeUtf8(Json.encodeToString(plugin.config))
            }
        }
        return path
    }

    /**
     * 获取插件进程根目录
     */
    fun pluginDataPath(plugin: Plugin): Path {
        val path = appBasePath / "container" / plugin.manifest.id
        if (!FileSystem.SYSTEM.exists(path)) {
            FileSystem.SYSTEM.createDirectories(path)
            val dataPath = appBasePath / "container" / plugin.manifest.id
            val cachePath = appBasePath / "container" / plugin.manifest.id
            FileSystem.SYSTEM.createDirectories(cachePath)
            FileSystem.SYSTEM.createDirectories(dataPath)
        }
        return path
    }

    fun loaderPath(): Path {
        @OptIn(ExperimentalNativeApi::class)
        val extension = when (Platform.osFamily) {
            OsFamily.WINDOWS -> ".exe"
            OsFamily.LINUX, OsFamily.MACOSX -> ""
            else -> error("Unsupported OSFamily ${Platform.osFamily}")
        }
        val path = appBasePath / "loader$extension"

        if (!FileSystem.SYSTEM.exists(path)) {
            error("loader$extension not exists.")
        }
        return path
    }
}
