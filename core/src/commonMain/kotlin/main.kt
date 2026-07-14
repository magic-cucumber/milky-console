import co.touchlab.kermit.Logger
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.validate
import kotlinx.coroutines.*
import okio.FileSystem
import okio.Path.Companion.toPath
import top.kagg886.milky.console.commands.ExitCommand
import top.kagg886.milky.console.terminal.Terminal

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
    private val basedir by option("--base-dir").validate {
        if (FileSystem.SYSTEM.metadataOrNull(it.toPath())?.isDirectory != true) {
            fail("$it is not a directory")
        }
    }

    override suspend fun run(): Unit = coroutineScope {
        Terminal(terminal, this).apply {
            setCommands(ExitCommand(), ExitCommand("/quit"))
            start()
            withContext(Dispatchers.IO) {
                awaitExit()
            }
        }
    }
}
