import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import okio.FileSystem
import okio.Path.Companion.toPath
import top.kagg886.milky.console.Application
import top.kagg886.milky.console.plugin.Plugin
import top.kagg886.milky.console.plugin.lifecycle.PluginOutboundEvent
import top.kagg886.milky.console.plugin.manifest
import top.kagg886.milky.console.protocol.HostClose
import top.kagg886.milky.console.util.eventbus.EventBus
import top.kagg886.milky.console.util.logger.MilkyConsoleDefaultLogWriter

private val base = FileSystem.SYSTEM.canonicalize(".".toPath()) / "milky-console"

private val logger = Logger.withTag("Core Application")

fun main(args: Array<String>) {
    Logger.setLogWriters(listOf(MilkyConsoleDefaultLogWriter))
    Logger.setMinSeverity(Severity.Info)
    try {
        runBlocking(Dispatchers.IO) {
            logger.i { "enter main: argCount=${args.size}, base=$base" }
            logger.d { "default log writer configured; initializing application" }
            Application.init(base)
            logger.i { "application initialized: bots=${Application.bots.size}, plugins=${Application.plugins.size}" }

            val failures = Application.bots.mapIndexed { index, bot ->
                async {
                    logger.v { "enter bot supervisor: index=$index" }
                    val exception = async(start = CoroutineStart.UNDISPATCHED) {
                        logger.v { "enter bot exception watcher: index=$index" }
                        bot.exceptionFlow.first().second
                    }
                    try {
                        logger.d { "starting bot lifecycle: index=$index" }
                        bot.start()
                        logger.i { "bot started: index=$index" }
                        bot.connectEvent()
                        logger.i { "milky event connection started: index=$index" }
                        exception.await().also {
                            logger.w(it) { "bot exception watcher completed: index=$index" }
                        }
                    } catch (throwable: Throwable) {
                        logger.e(throwable) { "bot lifecycle failed: index=$index" }
                        throwable
                    } finally {
                        exception.cancel()
                        logger.v { "exit bot supervisor: index=$index" }
                    }
                }
            }.awaitAll()
            logger.d { "bot supervisors completed: failures=${failures.size}, expectedAtLeastOne=${failures.isNotEmpty()}" }

            failures.forEach { exception -> logger.w(exception) { "milky exception" } }

            Application.bots.forEachIndexed { index, bot ->
                logger.v { "enter bot shutdown: index=$index" }
                bot.disconnectEvent()
                bot.close()
                logger.i { "bot shutdown completed: index=$index" }
            }

            val closeTask = Application.plugins.map { plugin ->
                logger.v { "enter plugin close request: id=${plugin.manifest.id}" }
                EventBus.post(PluginOutboundEvent(plugin.manifest.id, HostClose("all configured bots failed.")))
                async {
                    plugin.state.filterIsInstance<Plugin.State.Closed>().first().also {
                        logger.i { "plugin closed after host request: id=${plugin.manifest.id}, reason=${it.reason}" }
                    }
                }
            }
            logger.d { "posted close requests to plugins: count=${closeTask.size}" }

            closeTask.awaitAll()

            logger.i { "exit main successfully: pluginsClosed=${closeTask.size}" }
        }
    } catch (throwable: Throwable) {
        logger.a(throwable) { "core crashed" }
        throw throwable
    }
}
