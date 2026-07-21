package top.kagg886.milky.console.plugin.config

import com.akuleshov7.ktoml.Toml
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okio.FileSystem
import okio.Path
import org.ntqqrev.milky.Event
import org.ntqqrev.saltify.builtin.plugin.defaultLogging
import org.ntqqrev.saltify.core.SaltifyApplication
import org.ntqqrev.saltify.dsl.SaltifyPlugin
import org.ntqqrev.saltify.model.EventConnectionType
import top.kagg886.milky.console.Application
import top.kagg886.milky.console.plugin.lifecycle.PluginOutboundEvent
import top.kagg886.milky.console.plugin.manifest
import top.kagg886.milky.console.protocol.HostEvent
import top.kagg886.milky.console.util.eventbus.EventBus

@Serializable
data class HostConfig(
    val connections: List<HostConnectionConfig> = emptyList(),
) {
    companion object {
        fun load(fileSystem: FileSystem, path: Path): HostConfig =
            fileSystem.read(path) {
                Toml.decodeFromString(readUtf8())
            }
    }
}

/** Mirrors Saltify's [org.ntqqrev.saltify.dsl.config.ConnectionConfig]. */
@Serializable
data class HostConnectionConfig(
    val baseUrl: String = "",
    val accessToken: String? = null,
    val events: HostEventConnectionConfig = HostEventConnectionConfig(),
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
        install(EventBusForwardingPlugin)
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

private val EventBusForwardingPlugin = SaltifyPlugin("milky-console-event-bus") {
    on<Event> { event ->
        Application.plugins.forEach { plugin ->
            EventBus.post(PluginOutboundEvent(plugin.manifest.id, HostEvent(event)))
        }
    }
}
