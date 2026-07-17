/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/16 14:10
 * ================================================
 */

package top.kagg886.milky.console.plugin

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import org.ntqqrev.milky.ApiGeneralResponse
import org.ntqqrev.milky.Event
import top.kagg886.milky.console.plugin.lifecycle.PluginInboundEvent
import top.kagg886.milky.console.plugin.lifecycle.PluginOutboundEvent
import top.kagg886.milky.console.protocol.HostEvent
import top.kagg886.milky.console.protocol.PluginHandshakeError
import top.kagg886.milky.console.protocol.PluginApiRequest
import top.kagg886.milky.console.protocol.PluginApiResponse
import top.kagg886.milky.console.util.eventbus.EventBus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds

class PluginLoaderTest {
    @Test
    fun pluginCanRequestAndAwaitResponseFromOnLoad() = runBlocking {
        val container = pluginLoaderTestContainer().toPath()
        val registry = PluginRegistry(container)
        val pluginId = "plugin-loader-onload-request"

        suspend fun replyToNextRequest() {
            val request = withTimeout(10.seconds) {
                EventBus.subscribe<PluginInboundEvent>().first {
                    it.pluginId == pluginId && it.event is PluginApiRequest
                }.event as PluginApiRequest
            }
            EventBus.post(
                PluginOutboundEvent(
                    pluginId,
                    PluginApiResponse(
                        type = request.type,
                        tag = request.tag,
                        payload = ApiGeneralResponse(status = "ok", retcode = 0),
                    ),
                ),
            )
        }

        val response = launch(start = CoroutineStart.UNDISPATCHED) { replyToNextRequest() }
        val plugin = registry.make(container / "plugin" / pluginId)
        withTimeout(10.seconds) { response.join() }
        val ready = withTimeout(10.seconds) { plugin.state.filterIsInstance<Plugin.State.Ready>().first() }
        assertTrue(ready.process.kill())
        withTimeout(10.seconds) { plugin.state.filterIsInstance<Plugin.State.Closed>().first() }
        Unit
    }

    @Test
    fun pluginCanRequestAndAwaitResponseFromOnMessage() = runBlocking {
        val container = pluginLoaderTestContainer().toPath()
        val registry = PluginRegistry(container)
        val pluginId = "plugin-loader-onmessage-request"
        val plugin = registry.make(container / "plugin" / pluginId)
        withTimeout(10.seconds) { plugin.state.filterIsInstance<Plugin.State.Ready>().first() }

        val response = launch(start = CoroutineStart.UNDISPATCHED) {
            val request = withTimeout(10.seconds) {
                EventBus.subscribe<PluginInboundEvent>().first {
                    it.pluginId == pluginId && it.event is PluginApiRequest
                }.event as PluginApiRequest
            }
            EventBus.post(PluginOutboundEvent(pluginId, PluginApiResponse(request.type, request.tag, ApiGeneralResponse("ok", 0))))
        }
        EventBus.post(PluginOutboundEvent(pluginId, HostEvent(Event.BotOffline(0, 0, Event.BotOffline.Data("test")))))
        withTimeout(10.seconds) { response.join() }
        assertEquals(null, withTimeoutOrNull(250.milliseconds) { plugin.state.filterIsInstance<Plugin.State.Closed>().first() })
        assertTrue((plugin.state.value as Plugin.State.Ready).process.kill())
        withTimeout(10.seconds) { plugin.state.filterIsInstance<Plugin.State.Closed>().first() }
        Unit
    }

    @Test
    fun validPluginCompletesKilledHandshake() = runBlocking {
        val container = pluginLoaderTestContainer().toPath()
        val registry = PluginRegistry(container)
        val plugin = registry.make(container / "plugin" / "plugin-loader-test")

        val ready = withTimeout(10.seconds) {
            plugin.state.filterIsInstance<Plugin.State.Ready>().first()
        }
        assertIs<Plugin.State.Ready>(ready)

        assertTrue(ready.process.kill())
        withTimeout(10.seconds) {
            plugin.state.filterIsInstance<Plugin.State.Closed>().first()
        }
        Unit
    }

    @Test
    fun getApiCrashClosesPlugin() = runBlocking {
        val container = pluginLoaderTestContainer().toPath()
        val registry = PluginRegistry(container)
        assertHandshakeFailure(
            registry.make(container / "plugin" / "plugin-loader-crash"),
            "进程意外退出。",
            PluginHandshakeError.PROCESS_EXITED,
        )
    }

    @Test
    fun onLoadCrashClosesPlugin() = runBlocking {
        val container = pluginLoaderTestContainer().toPath()
        val registry = PluginRegistry(container)
        assertHandshakeFailure(
            registry.make(container / "plugin" / "plugin-loader-on-load-crash"),
            "进程意外退出。",
            PluginHandshakeError.PROCESS_EXITED,
        )
    }

    @Test
    fun missingEntryPointIsRejected() = runBlocking {
        val container = pluginLoaderTestContainer().toPath()
        val registry = PluginRegistry(container)
        assertHandshakeFailure(
            registry.make(container / "plugin" / "plugin-loader-missing-entry"),
            "无法查找到插件加载函数:",
            PluginHandshakeError.ENTRY_POINT_NOT_FOUND,
        )
    }

    @Test
    fun nullPluginApiIsRejected() = runBlocking {
        val container = pluginLoaderTestContainer().toPath()
        val registry = PluginRegistry(container)
        assertHandshakeFailure(
            registry.make(container / "plugin" / "plugin-loader-null-api"),
            "无法查找到插件加载函数: 插件加载函数返回值为空指针",
            PluginHandshakeError.NULL_PLUGIN_API,
        )
    }

    @Test
    fun incompatibleAbiIsRejected() = runBlocking {
        val container = pluginLoaderTestContainer().toPath()
        val registry = PluginRegistry(container)
        assertHandshakeFailure(
            registry.make(container / "plugin" / "plugin-loader-abi-mismatch"),
            "插件ABI不匹配。",
            PluginHandshakeError.ABI_MISMATCH,
        )
    }

    @Test
    fun tooSmallApiStructIsRejected() = runBlocking {
        val container = pluginLoaderTestContainer().toPath()
        val registry = PluginRegistry(container)
        assertHandshakeFailure(
            registry.make(container / "plugin" / "plugin-loader-short-api"),
            "插件API不匹配。",
            PluginHandshakeError.API_MISMATCH,
        )
    }

    @Test
    fun missingOnLoadIsRejected() = runBlocking {
        val container = pluginLoaderTestContainer().toPath()
        val registry = PluginRegistry(container)
        assertHandshakeFailure(
            registry.make(container / "plugin" / "plugin-loader-missing-on-load"),
            "插件API缺少 on_load",
            PluginHandshakeError.MISSING_ON_LOAD,
        )
    }

    @Test
    fun pluginInitializationFailureIsRejected() = runBlocking {
        val container = pluginLoaderTestContainer().toPath()
        val registry = PluginRegistry(container)
        assertHandshakeFailure(
            registry.make(container / "plugin" / "plugin-loader-on-load-failed"),
            "插件初始化失败",
            PluginHandshakeError.INITIALIZATION_FAILED,
        )
    }

    @Test
    fun pluginConfigurationFailureIsRejected() = runBlocking {
        val container = pluginLoaderTestContainer().toPath()
        val registry = PluginRegistry(container)
        assertHandshakeFailure(
            registry.make(container / "plugin" / "plugin-loader-config-rejected"),
            "插件初始化失败",
            PluginHandshakeError.INITIALIZATION_FAILED,
        )
    }

    private suspend fun assertHandshakeFailure(
        plugin: Plugin,
        expectedMessage: String,
        expectedError: PluginHandshakeError?,
    ) {
        val closed = withTimeout(10.seconds) {
            plugin.state.filterIsInstance<Plugin.State.Closed>().first()
        }
        val exception = assertIs<PluginhandshakeFailedException>(assertNotNull(closed.exception))
        assertEquals(expectedError, exception.error)
        assertTrue(
            exception.message.contains(expectedMessage),
            "Expected exception message to contain <$expectedMessage>, but was <${exception.message}>",
        )
    }
}

internal expect fun pluginLoaderTestContainer(): String
