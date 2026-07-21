import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import com.akuleshov7.ktoml.Toml
import top.kagg886.milky.console.plugin.config.HostConfig
import top.kagg886.milky.console.plugin.config.HostEventConnectionType

class HostConfigTest {
    @Test
    fun decodesEverySaltifyConnectionOption() {
        val config = Toml.decodeFromString(
            HostConfig.serializer(),
            """
            [[connections]]
            baseUrl = "http://localhost:30001"
            accessToken = "token"

            [connections.events]
            type = "SSE"
            autoReconnect = false
            baseReconnectionInterval = 1000
            maxReconnectionInterval = 20000
            maxReconnectionAttempts = 10

            [[connections]]
            baseUrl = "http://localhost:30002"
            """.trimIndent(),
        )

        assertEquals(2, config.connections.size)
        assertEquals("http://localhost:30001", config.connections[0].baseUrl)
        assertEquals("token", config.connections[0].accessToken)
        assertEquals(HostEventConnectionType.SSE, config.connections[0].events.type)
        assertEquals(false, config.connections[0].events.autoReconnect)
        assertEquals(1_000L, config.connections[0].events.baseReconnectionInterval)
        assertEquals(20_000L, config.connections[0].events.maxReconnectionInterval)
        assertEquals(10, config.connections[0].events.maxReconnectionAttempts)
        assertNull(config.connections[1].accessToken)
        assertEquals(HostEventConnectionType.WebSocket, config.connections[1].events.type)
    }

    @Test
    fun defaultProvidesALocalConnectionTemplate() {
        val connection = HostConfig.default().connections.single()

        assertEquals("http://localhost:30001", connection.baseUrl)
        assertEquals("", connection.accessToken)
        assertEquals(HostEventConnectionType.WebSocket, connection.events.type)
    }
}
