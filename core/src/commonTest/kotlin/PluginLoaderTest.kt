/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/16 14:10
 * ================================================
 */

package top.kagg886.milky.console.plugin

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.Severity.*
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.format
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
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds

class PluginLoaderTest {

    companion object {
        private val lock = reentrantLock()
        private val logger = object : LogWriter() {
            override fun log(
                severity: Severity,
                message: String,
                tag: String,
                throwable: Throwable?
            ) {
                val time = Clock.System.now()
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                    .format(
                        LocalDateTime.Format {
                            year()
                            chars("-")
                            monthNumber()
                            chars("-")
                            day()
                            chars(" ")
                            hour()
                            chars(":")
                            minute()
                            chars(":")
                            second()
                            chars(".")
                            secondFraction(3)
                        }
                    )

                val reset = "\u001B[0m"

                val (levelFg, levelBg, messageColor) = when (severity) {
                    Verbose -> Triple("\u001B[30m", "\u001B[47m", "\u001B[90m")
                    Debug   -> Triple("\u001B[30m", "\u001B[46m", "\u001B[37m")
                    Info    -> Triple("\u001B[30m", "\u001B[42m", "\u001B[36m")
                    Warn    -> Triple("\u001B[30m", "\u001B[43m", "\u001B[33m")
                    Error   -> Triple("\u001B[37m", "\u001B[41m", "\u001B[31m")
                    Assert  -> Triple("\u001B[37m", "\u001B[45m", "\u001B[35m")
                }

                fun String.cleanTag(): String =
                    filter { it.code !in 0x00..0x1F && it.code != 0x7F }
                        .let {
                            if (it.length > 24) it.take(21) + "..."
                            else it
                        }
                        .padEnd(24)

                val tagText = tag.cleanTag()
                val levelText = " ${severity.name.first()} "

                val plainLabel = buildString {
                    append(time)
                    append(" ")
                    append(levelText)
                    append(" ")
                    append("[")
                    append(tagText)
                    append("]")
                }

                val label = buildString {
                    append(time)
                    append("  ")
                    append(levelBg)
                    append(levelFg)
                    append(levelText)
                    append(reset)
                    append("  ")
                    append(tagText)
                }

                // ANSI长度不计入padding
                val padding = " ".repeat(plainLabel.length + 1)

                val message = buildString {
                    append(message)
                    if (throwable != null) {
                        appendLine()
                        append(throwable.stackTraceToString())
                    }
                }

                val all = buildString {
                    message.lineSequence().forEachIndexed { index, line ->
                        if (index == 0) {
                            append(
                                label +
                                        " " +
                                        messageColor +
                                        line +
                                        reset
                            )
                        } else {
                            append(
                                padding +
                                        messageColor +
                                        line +
                                        reset
                            )
                        }
                    }
                }

                lock.withLock {
                    println(all)
                }
            }
        }
    }

    @BeforeTest
    fun setLogWriters() {
        lock.withLock {
            if (!Logger.mutableConfig.logWriterList.contains(logger)) {
                Logger.setMinSeverity(gradleTestLoggerMinSeverity())
                Logger.setLogWriters(logger)
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
                    PluginApiResponse(request.type, request.tag, ApiGeneralResponse("ok", 0))
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
