package top.kagg886.milky.console

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path
import top.kagg886.milky.console.plugin.Plugin
import top.kagg886.milky.console.plugin.PluginRegistry
import top.kagg886.milky.console.plugin.config.HostConfig
import top.kagg886.milky.console.plugin.libpath
import top.kagg886.milky.console.plugin.lifecycle.PluginOutboundEvent
import top.kagg886.milky.console.plugin.manifest
import top.kagg886.milky.console.protocol.HostClose
import top.kagg886.milky.console.util.eventbus.EventBus
import top.kagg886.milky.console.util.raceN
import top.kagg886.milky.console.util.watcher.FileChange
import top.kagg886.milky.console.util.watcher.watchFileChange
import org.ntqqrev.saltify.core.SaltifyApplication

private val logger = Logger.withTag("Application")

object Application {
    private val fileSystem = FileSystem.SYSTEM
    private val lifecycleLock = Mutex()
    private val pluginWatchJobsLock = Mutex()
    private lateinit var registry: PluginRegistry
    private lateinit var configuredBots: List<SaltifyApplication>
    private val pluginWatchJobs = mutableMapOf<Plugin, Job>()

    val plugins: Set<Plugin>
        get() = registry.plugins

    val bots: List<SaltifyApplication>
        get() {
            logger.v { "enter bots getter: configured=${::configuredBots.isInitialized}" }
            check(::configuredBots.isInitialized) { "Application has not been initialized" }
            logger.v { "exit bots getter: count=${configuredBots.size}" }
            return configuredBots
        }

    suspend fun init(base: Path) {
        logger.i { "enter init: base=$base" }
        lifecycleLock.withLock {
            logger.v { "init lifecycle lock acquired: registryInitialized=${::registry.isInitialized}" }
            check(!::registry.isInitialized) { "Application has already been initialized" }

            if (fileSystem.metadataOrNull(base) == null) {
                logger.i { "base directory missing; creating default application files: base=$base" }
                fileSystem.createDirectories(base)
                HostConfig.writeDefault(fileSystem, base / "config.toml")
            } else {
                logger.v { "base directory exists: base=$base" }
            }
            check(fileSystem.metadataOrNull(base)?.isDirectory == true) { "Application base path must be a directory: $base" }

            logger.d { "loading host config: path=${base / "config.toml"}" }
            val hostConfig = HostConfig.load(fileSystem, base / "config.toml")
            check(hostConfig.connections.isNotEmpty()) { "config.toml must define at least one connection" }
            configuredBots = hostConfig.connections.map { it.createApplication() }
            logger.d { "host config loaded: connections=${hostConfig.connections.size}, bots=${configuredBots.size}" }

            fileSystem.createDirectories(base / "plugin")
            registry = PluginRegistry(base)
            scanLocked()
        }
        logger.i { "exit init successfully: base=$base, plugins=${registry.plugins.size}" }
    }

    suspend fun scan(): Set<Plugin> = lifecycleLock.withLock {
        logger.i { "enter scan" }
        check(::registry.isInitialized) { "Application has not been initialized" }
        scanLocked().also { logger.i { "exit scan successfully: plugins=${it.size}" } }
    }

    private suspend fun scanLocked(): Set<Plugin> {
        logger.v { "enter scanLocked: currentPlugins=${registry.plugins.size}" }
        val pluginBase = registry.appBasePath / "plugin"
        val candidates = fileSystem.list(pluginBase)
            .filter { fileSystem.metadataOrNull(it)?.isDirectory == true }
            .filterNot { path -> registry.plugins.any { it.basePath == path } }
        logger.d { "plugin scan candidates resolved: base=$pluginBase, candidates=${candidates.size}" }
        for (path in candidates) {
            logger.v { "enter candidate load: path=$path" }
            awaitLoaded(registry.make(path))
            logger.v { "exit candidate load: path=$path" }
        }

        for (plugin in registry.plugins) {
            watch(plugin)
        }
        logger.i { "load ${registry.plugins.size} plugins" }
        logger.v { "exit scanLocked: plugins=${registry.plugins.size}" }
        return registry.plugins
    }

    private suspend fun awaitLoaded(plugin: Plugin) {
        logger.v { "enter awaitLoaded: plugin=${plugin.basePath}, state=${plugin.state.value}" }
        val state = plugin.state.first { it is Plugin.State.Ready || it is Plugin.State.Closed }
        if (state is Plugin.State.Closed) {
            logger.w("plugin ${plugin.basePath.name} load failed: ${state.reason}", state.exception)
        } else {
            logger.i { "plugin ${plugin.basePath.name} load reached ready state" }
        }
        logger.v { "exit awaitLoaded: plugin=${plugin.basePath}, state=$state" }
    }

    private suspend fun watch(plugin: Plugin) {
        logger.v { "enter watch: plugin=${plugin.basePath}" }
        pluginWatchJobsLock.withLock {
            if (pluginWatchJobs.containsKey(plugin)) {
                logger.d { "plugin watch already registered; skipping: plugin=${plugin.basePath}" }
                return
            }
            pluginWatchJobs[plugin] = registry.scope.launch {
                logger.i { "plugin watch job started: plugin=${plugin.basePath.name}" }
                try {
                    val signal = raceN(
                        {
                            PluginWatchSignal.Closed(
                                plugin.state.filterIsInstance<Plugin.State.Closed>().first(),
                            )
                        },
                        {
                            PluginWatchSignal.FileChanged(
                                plugin.manifestPath,
                                fileSystem.watchFileChange(plugin.manifestPath).first(),
                            )
                        },
                        {
                            PluginWatchSignal.FileChanged(
                                plugin.libpath,
                                fileSystem.watchFileChange(plugin.libpath).first(),
                            )
                        },
                    )

                    when (signal) {
                        is PluginWatchSignal.Closed -> {
                            val reason = signal.state.reason
                            if (!reason.shouldReload) {
                                logger.i { "plugin ${plugin.basePath.name} closed without reload: $reason" }
                                return@launch
                            }
                            logger.w("plugin ${plugin.basePath.name} closed; reloading: $reason", reason.exception)
                        }

                        is PluginWatchSignal.FileChanged -> {
                            logger.i {
                                "plugin ${plugin.basePath.name} source changed: ${signal.path}, change=${signal.change}; reloading"
                            }
                            EventBus.post(
                                PluginOutboundEvent(plugin.manifest.id, HostClose("plugin source changed")),
                            )
                            plugin.state.filterIsInstance<Plugin.State.Closed>().first()
                        }
                    }

                    logger.d { "plugin watch signal handled; reloading: plugin=${plugin.basePath.name}" }
                    reload(plugin.basePath)
                } finally {
                    pluginWatchJobsLock.withLock {
                        pluginWatchJobs.remove(plugin)
                        logger.v { "plugin watch job removed: plugin=${plugin.basePath.name}, remaining=${pluginWatchJobs.size}" }
                    }
                }
            }
            logger.d { "plugin watch registered: plugin=${plugin.basePath.name}, total=${pluginWatchJobs.size}" }
        }
        logger.v { "exit watch: plugin=${plugin.basePath}" }
    }

    private suspend fun reload(path: Path) {
        logger.i { "enter reload: path=$path" }
        lifecycleLock.withLock {
            if (fileSystem.metadataOrNull(path)?.isDirectory != true) {
                logger.i { "plugin ${path.name} was removed; skipping reload" }
                return
            }
            if (registry.plugins.any { it.basePath == path }) {
                logger.d { "plugin ${path.name} is already registered; skipping duplicate reload" }
                return
            }

            val replacement = registry.make(path)
            awaitLoaded(replacement)
            if (replacement in registry.plugins) watch(replacement)
            logger.d { "reload completed: path=$path, registered=${replacement in registry.plugins}" }
        }
        logger.i { "exit reload: path=$path" }
    }

    private sealed interface PluginWatchSignal {
        data class Closed(val state: Plugin.State.Closed) : PluginWatchSignal
        data class FileChanged(val path: Path, val change: FileChange) : PluginWatchSignal
    }
}
