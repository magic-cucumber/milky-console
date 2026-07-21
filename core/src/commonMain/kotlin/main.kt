import co.touchlab.kermit.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import okio.FileSystem
import okio.Path.Companion.toPath
import org.ntqqrev.saltify.builtin.plugin.defaultLogging
import org.ntqqrev.saltify.core.SaltifyApplication
import org.ntqqrev.saltify.model.EventConnectionState
import org.ntqqrev.saltify.model.EventConnectionType
import top.kagg886.milky.console.Application
import top.kagg886.milky.console.plugin.Plugin
import top.kagg886.milky.console.plugin.PluginRegistry
import top.kagg886.milky.console.plugin.lifecycle.PluginOutboundEvent
import top.kagg886.milky.console.plugin.manifest
import top.kagg886.milky.console.protocol.HostClose
import top.kagg886.milky.console.protocol.HostEvent
import top.kagg886.milky.console.util.eventbus.EventBus
import top.kagg886.milky.console.util.logger.MilkyConsoleDefaultLogWriter

private val base = FileSystem.SYSTEM.canonicalize("milky-console".toPath())

private val logger = Logger.withTag("Core Application")

fun main(args: Array<String>): Unit = runBlocking(Dispatchers.IO) {
    Logger.setLogWriters(listOf(MilkyConsoleDefaultLogWriter))
    Application.init(base)

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
            for (plugin in Application.plugins) {
                EventBus.post(PluginOutboundEvent(plugin.manifest.id, HostEvent(it)))
            }
        }
    }

    val ex = milky.exceptionFlow.first()

    logger.w(ex.second) { "milky exception" }

    job.cancel()
    milky.disconnectEvent()
    milky.close()

    Application.plugins.map {
        EventBus.post(PluginOutboundEvent(it.manifest.id, HostClose("milky server disconnected.")))
        async {
            it.state.filterIsInstance<Plugin.State.Closed>().first()
        }
    }.awaitAll()

    logger.i("plugin exited successfully.")
}
