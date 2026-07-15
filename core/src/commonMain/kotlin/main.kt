import co.touchlab.kermit.Logger
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.validate
import kotlinx.coroutines.*
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import top.kagg886.milky.console.terminal.commands.ExitCommand
import top.kagg886.milky.console.terminal.Terminal
import top.kagg886.milky.console.plugin.PluginRegistry
import kotlin.getValue

/**
 * ================================================
 * Author:     886kagg
 * Created on: 2026/7/13 19:51
 * ================================================
 */

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
fun main(args: Array<String>) {
    Logger.setLogWriters()
    val dispatcher = newSingleThreadContext("Renderer")
    runBlocking(dispatcher) {
        ApplicationCommand().main(args)
    }
    dispatcher.close()
}

class ApplicationCommand : SuspendingCliktCommand() {
    private val basedir by option("--base-dir").convert { it.toPath() }.default(".".toPath()).validate {
        if (FileSystem.SYSTEM.metadataOrNull(it)?.isDirectory != true) {
            fail("$it is not a directory")
        }
    }

    private val pluginPath by lazy {
        basedir / "plugin"
    }

    private val loaderPath by lazy {
        basedir / PluginRegistry.loaderExecutableFileName
    }

    private val dataPath by lazy {
        basedir / "data"
    }

    private val cachePath by lazy {
        basedir / "cache"
    }

    private val loggerPath by lazy {
        basedir / "logs"
    }


    override suspend fun run(): Unit = coroutineScope {
        try {
            PluginRegistry.loadAll(pluginPath, loaderPath)
            val terminal = Terminal(terminal, this).apply {
                setCommands(ExitCommand(), ExitCommand("/quit"))
                start()
            }
            withContext(Dispatchers.IO) {
                terminal.awaitExit()
            }
        } finally {
            PluginRegistry.close()
        }
    }
}
