import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import org.ntqqrev.saltify.builtin.plugin.defaultLogging
import org.ntqqrev.saltify.core.SaltifyApplication
import org.ntqqrev.saltify.core.setAvatar
import org.ntqqrev.saltify.model.EventConnectionState
import org.ntqqrev.saltify.model.EventConnectionType
import top.kagg886.milky.console.plugin.Plugin
import top.kagg886.milky.console.plugin.PluginRegistry
import top.kagg886.milky.console.plugin.lifecycle.PluginInboundEvent
import top.kagg886.milky.console.plugin.lifecycle.PluginOutboundEvent
import top.kagg886.milky.console.plugin.manifest
import top.kagg886.milky.console.protocol.HostClose
import top.kagg886.milky.console.protocol.HostEvent
import top.kagg886.milky.console.util.eventbus.EventBus
import top.kagg886.milky.console.util.logger.MilkyConsoleDefaultLogWriter
import top.kagg886.milky.console.util.raceN

private val base = FileSystem.SYSTEM.canonicalize("milky-console".toPath())

private val logger = Logger.withTag("Core Application")

fun main(args: Array<String>): Unit = runBlocking(Dispatchers.IO) {
    Logger.setLogWriters(listOf(MilkyConsoleDefaultLogWriter))
    val fs = FileSystem.SYSTEM
    if (fs.metadataOrNull(base)?.isDirectory != true) {
        fs.createDirectories(base)
    }

    val pluginBase = base / "plugin"
    if (fs.metadataOrNull(pluginBase)?.isDirectory != true) {
        fs.createDirectories(pluginBase)
    }

    val registry = PluginRegistry(base)

    val autoReloadPlugins = fs.list(pluginBase).map { registry.make(it) }.map { plugin ->
        async {
            val state = plugin.state.first { it is Plugin.State.Ready || it is Plugin.State.Closed }
            if (state is Plugin.State.Closed) {
                logger.w("plugin ${plugin.basePath.name} load failed", state.exception)
            }
            if (state is Plugin.State.Ready) plugin else null
        }
    }.awaitAll().filterNotNull()

    logger.i("load ${registry.plugins.size} plugins")


    val milky = SaltifyApplication {
        connection {
            baseUrl = "http://localhost:30001"
            accessToken = "" // 访问令牌

            events {
                type = EventConnectionType.WebSocket // 可选 WebSocket 或 SSE
                autoReconnect = true
            }
        }

        install(defaultLogging)
    }

    milky.start()
    milky.connectEvent()
    milky.eventConnectionStateFlow.first { it is EventConnectionState.Connected }
    logger.i { "milky connected" }

    val job = launch {
        logger.d { "start collect event" }
        milky.eventFlow.collectLatest {
            logger.v("receive event: $it")
            for (plugin in registry.plugins) {
                EventBus.post(PluginOutboundEvent(plugin.manifest.id, HostEvent(it)))
            }
        }
    }

    val autoReloadJob = launch {
        for (i in autoReloadPlugins) {
            i.state.collect {
                if (it is Plugin.State.Closed && it.exception != null) {

                }
            }
        }
    }

    val ex = milky.exceptionFlow.first()

    logger.w(ex.second) { "milky exception" }

    job.cancel()
    milky.disconnectEvent()
    milky.close()

    registry.plugins.map {
        EventBus.post(PluginOutboundEvent(it.manifest.id, HostClose("milky server disconnected.")))
        async {
            it.state.filterIsInstance<Plugin.State.Closed>().first()
        }
    }.awaitAll()

    logger.i("plugin exited successfully.")
}
