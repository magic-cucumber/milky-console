package top.kagg886.milky.console.plugin.config

import co.touchlab.kermit.Logger
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

private val logger = Logger.withTag("HostConfig")

@Serializable
data class HostConfig(
    val connections: List<HostConnectionConfig> = emptyList(),
) {
    companion object {
        fun default(): HostConfig {
            logger.i { "enter default" }
            val config = HostConfig(
                connections = listOf(
                    HostConnectionConfig(
                        baseUrl = "http://localhost:3001",
                        accessToken = "",
                        botQQ = 0u,
                    ),
                ),
            )
            logger.d { "default host config created: connections=${config.connections.size}" }
            logger.i { "exit default successfully" }
            return config
        }

        fun load(fileSystem: FileSystem, path: Path): HostConfig {
            logger.i { "enter load: path=$path" }
            return fileSystem.read(path) {
                val raw = readUtf8()
                logger.d { "host config file read: path=$path, bytes=${raw.length}" }
                Toml.decodeFromString<HostConfig>(raw).also {
                    logger.i { "exit load successfully: path=$path, connections=${it.connections.size}" }
                }
            }
        }

        fun writeDefault(fileSystem: FileSystem, path: Path) {
            logger.i { "enter writeDefault: path=$path" }
            fileSystem.write(path) {
                val encoded = Toml.encodeToString(default())
                writeUtf8(encoded)
                logger.d { "default host config written: path=$path, bytes=${encoded.length}" }
            }
            logger.i { "exit writeDefault successfully: path=$path" }
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
        logger.i { "enter createApplication: baseUrl=$baseUrl, botQQ=$botQQ, eventType=${events.type}" }
        connection {
            logger.v { "enter saltify connection config: botQQ=$botQQ" }
            baseUrl = this@HostConnectionConfig.baseUrl
            accessToken = this@HostConnectionConfig.accessToken
            events {
                logger.v { "enter saltify events config: botQQ=$botQQ" }
                type = this@HostConnectionConfig.events.type.toSaltifyType()
                autoReconnect = this@HostConnectionConfig.events.autoReconnect
                baseReconnectionInterval = this@HostConnectionConfig.events.baseReconnectionInterval
                maxReconnectionInterval = this@HostConnectionConfig.events.maxReconnectionInterval
                maxReconnectionAttempts = this@HostConnectionConfig.events.maxReconnectionAttempts
                logger.d { "saltify events config applied: botQQ=$botQQ, type=$type, autoReconnect=$autoReconnect" }
            }
            logger.d { "saltify connection config applied: baseUrl=$baseUrl, hasAccessToken=${accessToken != null}" }
        }

        install(defaultLogging)
        logger.d { "default saltify logging installed: botQQ=$botQQ" }
        install(EventBusForwardingPlugin) {
            selfId = botQQ
            logger.d { "event bus forwarding plugin configured: selfId=$selfId" }
        }
        logger.i { "exit createApplication successfully: botQQ=$botQQ" }
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
        WebSocket -> {
            logger.v { "event connection type branch: WebSocket" }
            EventConnectionType.WebSocket
        }
        SSE -> {
            logger.v { "event connection type branch: SSE" }
            EventConnectionType.SSE
        }
    }
}

class EventBusForwardingPluginConfig {
    var selfId: ULong by Delegates.notNull()
}

private val EventBusForwardingPlugin =
    SaltifyPlugin("milky-console-event-bus", ::EventBusForwardingPluginConfig) { config ->
        onStart {
            top.kagg886.milky.console.plugin.config.logger.i { "enter EventBusForwardingPlugin.onStart: selfId=${config.selfId}" }
            val actualUin = client.getLoginInfo().uin
            if (actualUin != config.selfId.toLong()) {
                top.kagg886.milky.console.plugin.config.logger.e { "bot identity mismatch: configured=${config.selfId}, actual=$actualUin" }
                error("配置声明的bot qq和实际登录的qq不匹配，请重新配置。")
            }
            launch(start = CoroutineStart.UNDISPATCHED) {
                top.kagg886.milky.console.plugin.config.logger.v { "enter plugin API forwarding collector: selfId=${config.selfId}" }
                EventBus.subscribe<PluginInboundEvent>()
                    .filter { it.event is PluginApiRequest && it.event.uin == config.selfId }
                    .map { it.pluginId to it.event as PluginApiRequest }
                    .collect { (pluginId, request) ->
                        top.kagg886.milky.console.plugin.config.logger.i { "plugin API request received: plugin=$pluginId, category=${request.category}, tag=${request.tag}" }
                        val result = runCatching {
                            client.callApi(request.category, request.payload)
                        }
                        val response = result.getOrElse { throwable ->
                            top.kagg886.milky.console.plugin.config.logger.w(throwable) { "plugin API request failed; returning failed response: plugin=$pluginId, tag=${request.tag}" }
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
                        top.kagg886.milky.console.plugin.config.logger.d { "plugin API response posted: plugin=$pluginId, tag=${request.tag}, status=${response.status}" }
                    }
            }
            top.kagg886.milky.console.plugin.config.logger.i { "exit EventBusForwardingPlugin.onStart: selfId=${config.selfId}" }
        }
        on<Event> { event ->
            top.kagg886.milky.console.plugin.config.logger.i { "enter EventBusForwardingPlugin.onEvent: type=${event::class.simpleName}, plugins=${Application.plugins.size}" }
            Application.plugins.forEach { plugin ->
                EventBus.post(PluginOutboundEvent(plugin.manifest.id, HostEvent(event)))
                top.kagg886.milky.console.plugin.config.logger.v { "host event forwarded to plugin: id=${plugin.manifest.id}, type=${event::class.simpleName}" }
            }
            top.kagg886.milky.console.plugin.config.logger.d { "host event forwarding completed: type=${event::class.simpleName}, pluginCount=${Application.plugins.size}" }
        }
    }
