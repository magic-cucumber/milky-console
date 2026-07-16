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

private val log = Logger.withTag("PluginRegistry")

class PluginRegistry(val appBasePath: Path) {
    init {
        log.i { ">>> PluginRegistry(appBasePath=$appBasePath) init enter" }
        log.v { "init: checking appBasePath.isAbsolute" }
        if (appBasePath.isAbsolute.not()) {
            log.e { "appBasePath is not absolute: $appBasePath" }
            error("appBasePath should be absolute")
        }
        log.v { "init: checking appBasePath is a directory" }
        if (FileSystem.SYSTEM.metadataOrNull(appBasePath)?.isDirectory != true) {
            log.e { "appBasePath is not a directory: $appBasePath" }
            error("appBasePath should be a directory and exists")
        }
        log.d { "[group: dir-setup] appBasePath validated, creating config/ and container/ directories" }
        FileSystem.SYSTEM.createDirectories(appBasePath / "config")
        FileSystem.SYSTEM.createDirectories(appBasePath / "container")
        log.i { "<<< PluginRegistry(appBasePath=$appBasePath) init exit" }
    }

    private val _plugins = mutableSetOf<Plugin>()
    private val pluginsModifyLock = Mutex()
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun make(path: Path): Plugin {
        log.i { ">>> PluginRegistry.make(path=$path) enter" }
        val impl = Plugin(path)
        log.v { "make: Plugin instance created, starting verify()" }
        if (impl.verify()) {
            log.v { "make: verify() succeeded, adding plugin to set" }
            pluginsModifyLock.withLock {
                _plugins.add(impl)
                log.d { "[group: plugin-set] plugin added, total active plugins=${_plugins.size}" }
            }

            scope.launch {
                log.v { "make: launching handshake() in scope for ${impl.manifest.id}" }
                if (!impl.handshake(this@PluginRegistry)) {
                    log.w { "make: handshake() failed for ${impl.manifest.id}, removing plugin" }
                    pluginsModifyLock.withLock {
                        _plugins.remove(impl)
                    }
                } else {
                    log.d { "[group: handshake] handshake() succeeded for ${impl.manifest.id}" }
                }
            }
        } else {
            log.v { "make: verify() failed, plugin will not be added" }
            log.d { "[group: verify] verify() result=false, expected=true, match=false" }
        }
        log.i { "<<< PluginRegistry.make() exit, plugin=${impl.manifest.id}" }
        return impl
    }

    fun remove(plugin: Plugin) {
        log.i { ">>> PluginRegistry.remove(plugin=${plugin.manifest.id}) enter" }
        log.v { "remove: checking if plugin state is Closed" }
        if (plugin.state.value is Plugin.State.Closed) {
            _plugins.remove(plugin)
            log.d { "[group: plugin-remove] plugin removed, remaining=${_plugins.size}" }
        } else {
            log.w { "remove: plugin state is ${plugin.state.value::class.simpleName}, not Closed, skipping removal" }
        }
        log.i { "<<< PluginRegistry.remove() exit" }
    }

    fun pluginConfigPath(plugin: Plugin): Path {
        log.i { ">>> PluginRegistry.pluginConfigPath(plugin=${plugin.manifest.id}) enter" }
        val path = appBasePath / "config" / "${plugin.manifest.id}.json"
        log.v { "pluginConfigPath: checking if $path exists" }
        if (!FileSystem.SYSTEM.exists(path)) {
            log.v { "pluginConfigPath: path does not exist, creating with default config" }
            FileSystem.SYSTEM.write(path) {
                writeUtf8(Json.encodeToString(plugin.config))
            }
            log.d { "[group: config-file] default config written to $path" }
        } else {
            log.v { "pluginConfigPath: path already exists" }
        }
        log.i { "<<< PluginRegistry.pluginConfigPath() exit, path=$path" }
        return path
    }

    fun pluginDataPath(plugin: Plugin): Path {
        log.i { ">>> PluginRegistry.pluginDataPath(plugin=${plugin.manifest.id}) enter" }
        val path = appBasePath / "container" / plugin.manifest.id
        log.v { "pluginDataPath: checking if $path exists" }
        if (!FileSystem.SYSTEM.exists(path)) {
            log.v { "pluginDataPath: path does not exist, creating directories" }
            FileSystem.SYSTEM.createDirectories(path)
            val dataPath = appBasePath / "container" / plugin.manifest.id
            val cachePath = appBasePath / "container" / plugin.manifest.id
            FileSystem.SYSTEM.createDirectories(cachePath)
            FileSystem.SYSTEM.createDirectories(dataPath)
            log.d { "[group: data-dir] container directories created for ${plugin.manifest.id}" }
        } else {
            log.v { "pluginDataPath: path already exists" }
        }
        log.i { "<<< PluginRegistry.pluginDataPath() exit, path=$path" }
        return path
    }

    fun loaderPath(): Path {
        log.i { ">>> PluginRegistry.loaderPath() enter" }
        @OptIn(ExperimentalNativeApi::class)
        val extension = when (Platform.osFamily) {
            OsFamily.WINDOWS -> {
                log.v { "loaderPath: OS=WINDOWS, extension=.exe" }
                ".exe"
            }
            OsFamily.LINUX, OsFamily.MACOSX -> {
                log.v { "loaderPath: OS=${Platform.osFamily.name}, extension=" }
                ""
            }
            else -> {
                log.e { "loaderPath: Unsupported OSFamily ${Platform.osFamily}" }
                error("Unsupported OSFamily ${Platform.osFamily}")
            }
        }
        val path = appBasePath / "loader$extension"
        log.v { "loaderPath: checking if $path exists" }

        if (!FileSystem.SYSTEM.exists(path)) {
            log.e { "loader$extension not found at $path" }
            error("loader$extension not exists.")
        }
        log.d { "[group: loader-path] loader found at $path" }
        log.i { "<<< PluginRegistry.loaderPath() exit, path=$path" }
        return path
    }
}
