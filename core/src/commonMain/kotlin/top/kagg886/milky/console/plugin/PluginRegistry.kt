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
import top.kagg886.milky.console.plugin.config.PluginManifest
import top.kagg886.milky.console.plugin.lifecycle.handshake
import top.kagg886.milky.console.plugin.lifecycle.verify
import kotlin.experimental.ExperimentalNativeApi

private val logger = Logger.withTag("PluginRegistry")


class PluginRegistry(val appBasePath: Path) {
    init {
        logger.i { "enter init: appBasePath=$appBasePath" }

        if (appBasePath.isAbsolute.not()) {
            logger.e { "invalid appBasePath: path is not absolute: $appBasePath" }
            error("appBasePath should be absolute")
        }
        if (FileSystem.SYSTEM.metadataOrNull(appBasePath)?.isDirectory != true) {
            logger.e { "invalid appBasePath: directory does not exist: $appBasePath" }
            error("appBasePath should be a directory and exists")
        }
        FileSystem.SYSTEM.createDirectories(appBasePath / "config")
        FileSystem.SYSTEM.createDirectories(appBasePath / "container")
        logger.d { "registry directories ensured: config=${appBasePath / "config"}, container=${appBasePath / "container"}" }
        logger.i { "exit init successfully: config/container directories ready" }
    }

    private val _plugins = mutableSetOf<Plugin>()
    val plugins
        get() = _plugins.toSet()
    private val pluginsModifyLock = Mutex()
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun make(path: Path): Plugin {
        logger.i { "enter make: path=$path, plugins=${_plugins.size}" }
        val impl = Plugin(path)
        if (impl.verify(this)) {
            logger.d { "verify succeeded: pluginId=${impl.manifest.id}" }
            val pluginId = impl.manifest.id
            pluginsModifyLock.withLock {
                _plugins.add(impl)
                logger.d { "plugin added to registry: id=$pluginId, total=${_plugins.size}" }
            }

            scope.launch {
                logger.v { "enter handshake job: id=$pluginId" }
                if (!impl.handshake(this@PluginRegistry)) {
                    logger.w { "handshake exited unsuccessfully; removing plugin: id=$pluginId" }
                    remove(impl)
                } else {
                    logger.d { "handshake completed successfully: id=$pluginId, state=${impl.state.value}" }
                }
                logger.v { "exit handshake job: id=$pluginId" }
            }
        } else {
            logger.w { "verify exited unsuccessfully: path=$path, state=${impl.state.value}" }
        }
        logger.i { "exit make: path=$path, state=${impl.state.value}" }
        return impl
    }

    internal suspend fun remove(plugin: Plugin) {
        logger.i { "enter remove: plugin=${plugin.basePath}, state=${plugin.state.value}" }
        pluginsModifyLock.withLock {
            _plugins.remove(plugin)
            logger.d { "removed plugin; remaining=${_plugins.size}" }
        }
        logger.i { "exit remove: plugin=${plugin.basePath}, remaining=${_plugins.size}" }
    }
    /**
     * Returns the configuration-file path for a manifest that has been parsed but whose plugin
     * has not entered a manifest-initialized state yet.
     */
    internal fun pluginConfigPath(manifest: PluginManifest): Path {
        logger.v { "enter pluginConfigPath: id=${manifest.id}" }
        val path = appBasePath / "config" / "${manifest.id}.json"
        logger.v { "exit pluginConfigPath: id=${manifest.id}, path=$path" }
        return path
    }

    fun pluginDataPath(plugin: Plugin): Path {
        logger.i { "enter pluginDataPath: id=${plugin.manifest.id}" }
        val path = appBasePath / "container" / plugin.manifest.id
        if (!FileSystem.SYSTEM.exists(path)) {
            FileSystem.SYSTEM.createDirectories(path)
            val dataPath = appBasePath / "container" / plugin.manifest.id
            val cachePath = appBasePath / "container" / plugin.manifest.id
            FileSystem.SYSTEM.createDirectories(cachePath)
            FileSystem.SYSTEM.createDirectories(dataPath)
            logger.d { "created plugin data/cache directories: $path" }
        } else {
            logger.v { "plugin data directory already exists: $path" }
        }
        logger.i { "exit pluginDataPath: path=$path" }
        return path
    }

    fun loaderPath(): Path {
        logger.i { "enter loaderPath" }
        @OptIn(ExperimentalNativeApi::class)
        val extension = when (Platform.osFamily) {
            OsFamily.WINDOWS -> {
                logger.v { "loader extension branch: windows" }
                ".exe"
            }
            OsFamily.LINUX, OsFamily.MACOSX -> {
                logger.v { "loader extension branch: unix family=${Platform.osFamily}" }
                ""
            }
            else -> {
                logger.e { "unsupported OS family: ${Platform.osFamily}" }
                error("Unsupported OSFamily ${Platform.osFamily}")
            }
        }
        val path = appBasePath / "loader$extension"

        if (!FileSystem.SYSTEM.exists(path)) {
            logger.e { "loader executable missing: $path" }
            error("loader$extension not exists.")
        }
        logger.i { "exit loaderPath successfully: $path" }
        return path
    }
}
