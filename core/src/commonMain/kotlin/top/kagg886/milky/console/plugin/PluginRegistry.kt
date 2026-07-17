package top.kagg886.milky.console.plugin

import co.touchlab.kermit.Logger
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

private val pluginRegistryLogger = Logger.withTag("PluginRegistry")


class PluginRegistry(val appBasePath: Path) {
    init {
        pluginRegistryLogger.i { "enter init: appBasePath=$appBasePath" }
        
        if (appBasePath.isAbsolute.not()) {
            pluginRegistryLogger.e { "invalid appBasePath: path is not absolute: $appBasePath" }
            error("appBasePath should be absolute")
        }
        if (FileSystem.SYSTEM.metadataOrNull(appBasePath)?.isDirectory != true) {
            pluginRegistryLogger.e { "invalid appBasePath: directory does not exist: $appBasePath" }
            error("appBasePath should be a directory and exists")
        }
        FileSystem.SYSTEM.createDirectories(appBasePath / "config")
        FileSystem.SYSTEM.createDirectories(appBasePath / "container")
        pluginRegistryLogger.i { "exit init successfully: config/container directories ready" }
    }

    private val _plugins = mutableSetOf<Plugin>()
    private val pluginsModifyLock = Mutex()
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun make(path: Path): Plugin {
        pluginRegistryLogger.i { "enter make: path=$path, plugins=${_plugins.size}" }
        val impl = Plugin(path)
        if (impl.verify()) {
            pluginRegistryLogger.d { "verify succeeded: pluginId=${impl.manifest.id}" }
            val pluginId = impl.manifest.id
            pluginsModifyLock.withLock {
                _plugins.add(impl)
            }

            scope.launch {
                if (!impl.handshake(this@PluginRegistry)) {
                    pluginRegistryLogger.w { "handshake exited unsuccessfully; removing plugin: id=$pluginId" }
                    pluginsModifyLock.withLock {
                        _plugins.remove(impl)
                    }
                } else {
                    pluginRegistryLogger.d { "handshake completed successfully: id=$pluginId, state=${impl.state.value}" }
                }
            }
        } else {
            pluginRegistryLogger.w { "verify exited unsuccessfully: path=$path, state=${impl.state.value}" }
        }
        pluginRegistryLogger.i { "exit make: path=$path, state=${impl.state.value}" }
        return impl
    }

    fun remove(plugin: Plugin) {
        pluginRegistryLogger.i { "enter remove: plugin=${plugin.basePath}, state=${plugin.state.value}" }
        if (plugin.state.value is Plugin.State.Closed) {
            _plugins.remove(plugin)
            pluginRegistryLogger.d { "removed closed plugin; remaining=${_plugins.size}" }
        } else {
            pluginRegistryLogger.v { "remove skipped: plugin is not closed, state=${plugin.state.value}" }
        }
        pluginRegistryLogger.i { "exit remove: plugin=${plugin.basePath}, remaining=${_plugins.size}" }
    }

    fun pluginConfigPath(plugin: Plugin): Path {
        pluginRegistryLogger.i { "enter pluginConfigPath: id=${plugin.manifest.id}" }
        val path = appBasePath / "config" / "${plugin.manifest.id}.json"
        if (!FileSystem.SYSTEM.exists(path)) {
            FileSystem.SYSTEM.write(path) {
                writeUtf8(Json.encodeToString(plugin.config))
            }
            pluginRegistryLogger.d { "created plugin config: $path" }
        } else {
            pluginRegistryLogger.v { "plugin config already exists: $path" }
        }
        pluginRegistryLogger.i { "exit pluginConfigPath: path=$path" }
        return path
    }

    fun pluginDataPath(plugin: Plugin): Path {
        pluginRegistryLogger.i { "enter pluginDataPath: id=${plugin.manifest.id}" }
        val path = appBasePath / "container" / plugin.manifest.id
        if (!FileSystem.SYSTEM.exists(path)) {
            FileSystem.SYSTEM.createDirectories(path)
            val dataPath = appBasePath / "container" / plugin.manifest.id
            val cachePath = appBasePath / "container" / plugin.manifest.id
            FileSystem.SYSTEM.createDirectories(cachePath)
            FileSystem.SYSTEM.createDirectories(dataPath)
            pluginRegistryLogger.d { "created plugin data/cache directories: $path" }
        } else {
            pluginRegistryLogger.v { "plugin data directory already exists: $path" }
        }
        pluginRegistryLogger.i { "exit pluginDataPath: path=$path" }
        return path
    }

    fun loaderPath(): Path {
        pluginRegistryLogger.i { "enter loaderPath" }
        @OptIn(ExperimentalNativeApi::class)
        val extension = when (Platform.osFamily) {
            OsFamily.WINDOWS -> {
                ".exe"
            }
            OsFamily.LINUX, OsFamily.MACOSX -> {
                ""
            }
            else -> {
                pluginRegistryLogger.e { "unsupported OS family: ${Platform.osFamily}" }
                error("Unsupported OSFamily ${Platform.osFamily}")
            }
        }
        val path = appBasePath / "loader$extension"

        if (!FileSystem.SYSTEM.exists(path)) {
            pluginRegistryLogger.e { "loader executable missing: $path" }
            error("loader$extension not exists.")
        }
        pluginRegistryLogger.i { "exit loaderPath successfully: $path" }
        return path
    }
}
