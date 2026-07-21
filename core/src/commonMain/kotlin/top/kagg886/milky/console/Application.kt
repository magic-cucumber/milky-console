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

private val applicationLogger = Logger.withTag("Application")

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
            check(::configuredBots.isInitialized) { "Application has not been initialized" }
            return configuredBots
        }

    suspend fun init(base: Path) {
        lifecycleLock.withLock {
            check(!::registry.isInitialized) { "Application has already been initialized" }

            if (fileSystem.metadataOrNull(base) == null) {
                fileSystem.createDirectories(base)
                HostConfig.writeDefault(fileSystem, base / "config.toml")
            }
            check(fileSystem.metadataOrNull(base)?.isDirectory == true) { "Application base path must be a directory: $base" }

            val hostConfig = HostConfig.load(fileSystem, base / "config.toml")
            check(hostConfig.connections.isNotEmpty()) { "config.toml must define at least one connection" }
            configuredBots = hostConfig.connections.map { it.createApplication() }

            fileSystem.createDirectories(base / "plugin")
            registry = PluginRegistry(base)
            scanLocked()
        }
    }

    suspend fun scan(): Set<Plugin> = lifecycleLock.withLock {
        check(::registry.isInitialized) { "Application has not been initialized" }
        scanLocked()
    }

    private suspend fun scanLocked(): Set<Plugin> {
        val pluginBase = registry.appBasePath / "plugin"
        val candidates = fileSystem.list(pluginBase)
            .filter { fileSystem.metadataOrNull(it)?.isDirectory == true }
            .filterNot { path -> registry.plugins.any { it.basePath == path } }
        for (path in candidates) {
            awaitLoaded(registry.make(path))
        }

        for (plugin in registry.plugins) {
            watch(plugin)
        }
        applicationLogger.i { "load ${registry.plugins.size} plugins" }
        return registry.plugins
    }

    private suspend fun awaitLoaded(plugin: Plugin) {
        val state = plugin.state.first { it is Plugin.State.Ready || it is Plugin.State.Closed }
        if (state is Plugin.State.Closed) {
            applicationLogger.w("plugin ${plugin.basePath.name} load failed: ${state.reason}", state.exception)
        }
    }

    private suspend fun watch(plugin: Plugin) {
        pluginWatchJobsLock.withLock {
            if (pluginWatchJobs.containsKey(plugin)) return
            pluginWatchJobs[plugin] = registry.scope.launch {
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
                                applicationLogger.i { "plugin ${plugin.basePath.name} closed without reload: $reason" }
                                return@launch
                            }
                            applicationLogger.w("plugin ${plugin.basePath.name} closed; reloading: $reason", reason.exception)
                        }

                        is PluginWatchSignal.FileChanged -> {
                            applicationLogger.i {
                                "plugin ${plugin.basePath.name} source changed: ${signal.path}, change=${signal.change}; reloading"
                            }
                            EventBus.post(
                                PluginOutboundEvent(plugin.manifest.id, HostClose("plugin source changed")),
                            )
                            plugin.state.filterIsInstance<Plugin.State.Closed>().first()
                        }
                    }

                    reload(plugin.basePath)
                } finally {
                    pluginWatchJobsLock.withLock {
                        pluginWatchJobs.remove(plugin)
                    }
                }
            }
        }
    }

    private suspend fun reload(path: Path) {
        lifecycleLock.withLock {
            if (fileSystem.metadataOrNull(path)?.isDirectory != true) {
                applicationLogger.i { "plugin ${path.name} was removed; skipping reload" }
                return
            }
            if (registry.plugins.any { it.basePath == path }) {
                applicationLogger.d { "plugin ${path.name} is already registered; skipping duplicate reload" }
                return
            }

            val replacement = registry.make(path)
            awaitLoaded(replacement)
            if (replacement in registry.plugins) watch(replacement)
        }
    }

    private sealed interface PluginWatchSignal {
        data class Closed(val state: Plugin.State.Closed) : PluginWatchSignal
        data class FileChanged(val path: Path, val change: FileChange) : PluginWatchSignal
    }
}
