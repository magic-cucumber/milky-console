/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/16 14:10
 * ================================================
 */

package top.kagg886.milky.console.plugin

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.Severity.*
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import okio.Path.Companion.toPath
import org.ntqqrev.milky.ApiGeneralResponse
import org.ntqqrev.milky.Event
import top.kagg886.milky.console.plugin.lifecycle.PluginInboundEvent
import top.kagg886.milky.console.plugin.lifecycle.PluginOutboundEvent
import top.kagg886.milky.console.protocol.*
import top.kagg886.milky.console.util.eventbus.EventBus
import top.kagg886.milky.console.util.logger.MilkyConsoleDefaultLogWriter
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PluginLoaderTest {
    companion object {
        private val lock = reentrantLock()
    }

    @BeforeTest
    fun setLogWriters() {
        lock.withLock {
            if (!Logger.mutableConfig.logWriterList.contains(MilkyConsoleDefaultLogWriter)) {
                Logger.setMinSeverity(gradleTestLoggerMinSeverity())
                Logger.setLogWriters(MilkyConsoleDefaultLogWriter)
                log.log(gradleTestLoggerMinSeverity(),"test-event",null,"current log level is ${gradleTestLoggerMinSeverity()}")
            }
        }
    }

    private val log = Logger.withTag("test-event")

    @Test
    fun pluginCanRequestAndAwaitResponseFromOnLoad() = runBlocking {
        log.i { "Test [pluginCanRequestAndAwaitResponseFromOnLoad] start." }
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
                        category = request.category,
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
        log.i { "Test [pluginCanRequestAndAwaitResponseFromOnMessage] start." }
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
            EventBus.post(
                PluginOutboundEvent(
                    pluginId,
                    PluginApiResponse(request.category, request.tag, ApiGeneralResponse("ok", 0))
                )
            )
        }
        EventBus.post(PluginOutboundEvent(pluginId, HostEvent(Event.BotOffline(0, 0, Event.BotOffline.Data("test")))))
        withTimeout(10.seconds) { response.join() }
        assertEquals(
            null,
            withTimeoutOrNull(250.milliseconds) { plugin.state.filterIsInstance<Plugin.State.Closed>().first() })
        assertTrue((plugin.state.value as Plugin.State.Ready).process.kill())
        withTimeout(10.seconds) { plugin.state.filterIsInstance<Plugin.State.Closed>().first() }
        Unit
    }

    @Test
    fun validPluginCompletesKilledHandshake() = runBlocking {
        log.i { "Test [validPluginCompletesKilledHandshake] start." }
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
    fun hostCloseGracefullyStopsPlugin() = runBlocking {
        log.i { "Test [hostCloseGracefullyStopsPlugin] start." }
        val container = pluginLoaderTestContainer().toPath()
        val registry = PluginRegistry(container)
        val plugin = registry.make(container / "plugin" / "plugin-loader-test")

        withTimeout(10.seconds) { plugin.state.filterIsInstance<Plugin.State.Ready>().first() }
        log.i { "Test [hostCloseGracefullyStopsPlugin] state ready get." }
        plugin.send(HostClose("test shutdown"))
        val closed = withTimeout(10.seconds) {
            plugin.state.filterIsInstance<Plugin.State.Closed>().first()
        }
        assertNull(closed.exception)
        assertFalse(registry.plugins.contains(plugin))
    }

    @Test
    fun getApiCrashClosesPlugin() = runBlocking {
        log.i { "Test [getApiCrashClosesPlugin] start." }
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
        log.i { "Test [onLoadCrashClosesPlugin] start." }
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
        log.i { "Test [missingEntryPointIsRejected] start." }
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
        log.i { "Test [nullPluginApiIsRejected] start." }
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
        log.i { "Test [incompatibleAbiIsRejected] start." }
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
        log.i { "Test [tooSmallApiStructIsRejected] start." }
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
        log.i { "Test [missingOnLoadIsRejected] start." }
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
        log.i { "Test [pluginInitializationFailureIsRejected] start." }
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
        log.i { "Test [pluginConfigurationFailureIsRejected] start." }
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

internal expect fun gradleTestLoggerLevel(): String?

private fun gradleTestLoggerMinSeverity(): Severity = when (gradleTestLoggerLevel()?.uppercase()) {
    "DEBUG" -> Verbose
    "INFO" -> Debug
    "LIFECYCLE" -> Info
    "WARN" -> Warn
    "ERROR", "QUIET" -> Error
    else -> Info
}
