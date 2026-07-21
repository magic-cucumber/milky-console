package top.kagg886.milky.console.plugin.config

import com.akuleshov7.ktoml.Toml
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okio.FileSystem
import okio.Path
import org.ntqqrev.milky.ApiGeneralResponse
import org.ntqqrev.milky.Event
import org.ntqqrev.saltify.builtin.plugin.defaultLogging
import org.ntqqrev.saltify.core.SaltifyApplication
import org.ntqqrev.saltify.core.getLoginInfo
import org.ntqqrev.saltify.dsl.SaltifyPlugin
import org.ntqqrev.saltify.model.EventConnectionType
import top.kagg886.milky.console.Application
import top.kagg886.milky.console.plugin.callApi
import top.kagg886.milky.console.plugin.lifecycle.PluginInboundEvent
import top.kagg886.milky.console.plugin.lifecycle.PluginOutboundEvent
import top.kagg886.milky.console.plugin.manifest
import top.kagg886.milky.console.protocol.HostEvent
import top.kagg886.milky.console.protocol.PluginApiRequest
import top.kagg886.milky.console.protocol.PluginApiResponse
import top.kagg886.milky.console.util.eventbus.EventBus
import kotlin.properties.Delegates

@Serializable
data class HostConfig(
    val connections: List<HostConnectionConfig> = emptyList(),
) {
    companion object {
        fun default(): HostConfig = HostConfig(
            connections = listOf(
                HostConnectionConfig(
                    baseUrl = "http://localhost:3001",
                    accessToken = "",
                    botQQ = 0u,
                ),
            ),
        )

        fun load(fileSystem: FileSystem, path: Path): HostConfig =
            fileSystem.read(path) {
                Toml.decodeFromString(readUtf8())
            }

        fun writeDefault(fileSystem: FileSystem, path: Path) {
            fileSystem.write(path) {
                writeUtf8(Toml.encodeToString(default()))
            }
        }
    }
}

/** Mirrors Saltify's [org.ntqqrev.saltify.dsl.config.ConnectionConfig]. */
@Serializable
data class HostConnectionConfig(
    val baseUrl: String = "",
    val accessToken: String? = null,
    val events: HostEventConnectionConfig = HostEventConnectionConfig(),
    @SerialName("bot_qq")
    val botQQ: ULong = 0u,
) {
    fun createApplication(): SaltifyApplication = SaltifyApplication {
        connection {
            baseUrl = this@HostConnectionConfig.baseUrl
            accessToken = this@HostConnectionConfig.accessToken
            events {
                type = this@HostConnectionConfig.events.type.toSaltifyType()
                autoReconnect = this@HostConnectionConfig.events.autoReconnect
                baseReconnectionInterval = this@HostConnectionConfig.events.baseReconnectionInterval
                maxReconnectionInterval = this@HostConnectionConfig.events.maxReconnectionInterval
                maxReconnectionAttempts = this@HostConnectionConfig.events.maxReconnectionAttempts
            }
        }

        install(defaultLogging)
        install(EventBusForwardingPlugin) {
            selfId = botQQ
        }
    }
}

/** Mirrors Saltify's [org.ntqqrev.saltify.dsl.config.EventConnectionConfig]. */
@Serializable
data class HostEventConnectionConfig(
    val type: HostEventConnectionType = HostEventConnectionType.WebSocket,
    val autoReconnect: Boolean = true,
    val baseReconnectionInterval: Long = 500L,
    val maxReconnectionInterval: Long = 10_000L,
    val maxReconnectionAttempts: Int = 5,
)

@Serializable
enum class HostEventConnectionType {
    WebSocket,
    SSE,
    ;

    fun toSaltifyType(): EventConnectionType = when (this) {
        WebSocket -> EventConnectionType.WebSocket
        SSE -> EventConnectionType.SSE
    }
}

class EventBusForwardingPluginConfig {
    var selfId: ULong by Delegates.notNull()
}

private val EventBusForwardingPlugin =
    SaltifyPlugin("milky-console-event-bus", ::EventBusForwardingPluginConfig) { config ->
        onStart {
            if (client.getLoginInfo().uin != config.selfId.toLong()) {
                error("配置声明的bot qq和实际登录的qq不匹配，请重新配置。")
            }
            launch(start = CoroutineStart.UNDISPATCHED) {
                EventBus.subscribe<PluginInboundEvent>()
                    .filter { it.event is PluginApiRequest && it.event.uin == config.selfId }
                    .map { it.pluginId to it.event as PluginApiRequest }
                    .collect { (pluginId, request) ->
                        val result = runCatching {
                            client.callApi(request.category, request.payload)
                        }
                        val response = result.getOrElse { throwable ->
                            ApiGeneralResponse(
                                status = "failed",
                                retcode = -1,
                                message = throwable.message ?: "API request failed.",
                            )
                        }
                        EventBus.post(
                            PluginOutboundEvent(
                                pluginId,
                                PluginApiResponse(request.category, request.tag, response),
                            ),
                        )
                    }
            }
        }
        on<Event> { event ->
            Application.plugins.forEach { plugin ->
                EventBus.post(PluginOutboundEvent(plugin.manifest.id, HostEvent(event)))
            }
        }
    }
