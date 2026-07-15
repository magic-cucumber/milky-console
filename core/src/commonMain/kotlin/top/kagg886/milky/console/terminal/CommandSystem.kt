package top.kagg886.milky.console.terminal

import co.touchlab.kermit.Logger
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.parse
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.mordant.input.InputEvent
import com.github.ajalt.mordant.input.MouseTracking
import com.github.ajalt.mordant.terminal.PrintRequest
import com.github.ajalt.mordant.terminal.TerminalInterface
import com.github.ajalt.mordant.terminal.Terminal as MordantTerminal
import top.kagg886.milky.console.util.logger.asTaggedLogger
import kotlin.time.TimeMark

internal class CommandSystem(
    private val panelTerminal: MordantTerminal = MordantTerminal(),
    private val logger: Logger = "Command".asTaggedLogger,
) {
    private var root = createRoot()
    private var output: MutableList<String>? = null

    fun setCommands(vararg commands: SuspendingCliktCommand) {
        root = createRoot().subcommands(*commands)
    }

    suspend fun execute(input: String): List<String> {
        val argv = input.toArgv()
        if (argv.isEmpty()) return emptyList()

        val commandOutput = mutableListOf<String>()
        output = commandOutput
        try {
            root.parse(argv)
        } catch (error: CliktError) {
            root.getFormattedHelp(error)?.let(::emit)
        } finally {
            output = null
        }
        return commandOutput
    }

    private fun emit(message: Any?) {
        val text = message?.toString() ?: ""
        output?.add(text)
        logger.i(text)
    }

    private fun createRoot(): RootCommand {
        val terminalInterface = NonInteractiveTerminalInterface(
            delegate = panelTerminal.terminalInterface,
            emit = ::emit,
        )
        val commandTerminal = MordantTerminal(
            width = panelTerminal.size.width,
            height = panelTerminal.size.height,
            interactive = false,
            terminalInterface = terminalInterface,
        )
        return RootCommand(::emit, commandTerminal)
    }

    private class RootCommand(
        private val emit: (Any?) -> Unit,
        commandTerminal: MordantTerminal,
    ) : SuspendingCliktCommand(name = "milky-console") {
        init {
            context {
                terminal = commandTerminal
                echoMessage = { _, message, _, _ -> emit(message) }
            }
        }

        override suspend fun run() {
            if (currentContext.invokedSubcommand == null) {
                echoFormattedHelp()
            }
        }
    }

    internal companion object {
        /** Parses one command line using the console's shell-like quoting rules. */
        fun String.toArgv(): List<String> {
            val args = mutableListOf<String>()
            val current = StringBuilder()
            var state = ParseState.NORMAL
            var previous = ParseState.NORMAL

            for (char in this) {
                when (state) {
                    ParseState.NORMAL -> when {
                        char.isWhitespace() -> current.pushTo(args)
                        char == '\'' -> state = ParseState.SINGLE_QUOTE
                        char == '"' -> state = ParseState.DOUBLE_QUOTE
                        char == '\\' -> {
                            previous = state
                            state = ParseState.ESCAPE
                        }

                        else -> current.append(char)
                    }

                    ParseState.SINGLE_QUOTE -> when (char) {
                        '\'' -> state = ParseState.NORMAL
                        else -> current.append(char)
                    }

                    ParseState.DOUBLE_QUOTE -> when (char) {
                        '"' -> state = ParseState.NORMAL
                        '\\' -> {
                            previous = state
                            state = ParseState.ESCAPE
                        }

                        else -> current.append(char)
                    }

                    ParseState.ESCAPE -> {
                        current.append(char)
                        state = previous
                    }
                }
            }

            current.pushTo(args)
            return args
        }
    }
}


private class NonInteractiveTerminalInterface(
    private val delegate: TerminalInterface,
    private val emit: (Any?) -> Unit,
) : TerminalInterface by delegate {
    override fun completePrintRequest(request: PrintRequest) {
        emit(request.text)
    }

    override fun readLineOrNull(hideInput: Boolean): String? = null

    override fun readInputEvent(timeout: TimeMark, mouseTracking: MouseTracking): InputEvent? {
        throw UnsupportedOperationException("Interactive input is disabled for console commands")
    }

    override fun enterRawMode(mouseTracking: MouseTracking): AutoCloseable {
        throw UnsupportedOperationException("Interactive input is disabled for console commands")
    }
}

private enum class ParseState {
    NORMAL,
    SINGLE_QUOTE,
    DOUBLE_QUOTE,
    ESCAPE,
}

private fun StringBuilder.pushTo(args: MutableList<String>) {
    if (isNotEmpty()) {
        args += toString()
        clear()
    }
}
