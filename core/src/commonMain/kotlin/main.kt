import co.touchlab.kermit.Logger
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

fun main(args: Array<String>): Unit = runBlocking(Dispatchers.IO) {
    Logger.setLogWriters(listOf(MilkyConsoleDefaultLogWriter))
    Application.init(base)

    val failures = Application.bots.map { bot ->
        async {
            val exception = async(start = CoroutineStart.UNDISPATCHED) {
                bot.exceptionFlow.first().second
            }
            try {
                bot.start()
                bot.connectEvent()
                logger.i { "milky event connection started" }
                exception.await()
            } catch (throwable: Throwable) {
                throwable
            } finally {
                exception.cancel()
            }
        }
    }.awaitAll()

    failures.forEach { exception -> logger.w(exception) { "milky exception" } }

    Application.bots.forEach { bot ->
        bot.disconnectEvent()
        bot.close()
    }

    val closeTask = Application.plugins.map {
        EventBus.post(PluginOutboundEvent(it.manifest.id, HostClose("all configured bots failed.")))
        async {
            it.state.filterIsInstance<Plugin.State.Closed>().first()
        }
    }

    closeTask.awaitAll()

    logger.i("plugin exited successfully.")
}
