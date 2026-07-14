package top.kagg886.milky.console.terminal

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.mordant.animation.animation
import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.input.enterRawModeOrNull
import com.github.ajalt.mordant.input.isCtrlC
import com.github.ajalt.mordant.rendering.TextColors.brightCyan
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.inverse
import com.github.ajalt.mordant.table.ColumnWidth
import com.github.ajalt.mordant.table.verticalLayout
import com.github.ajalt.mordant.terminal.Terminal as MordantTerminal
import com.github.ajalt.mordant.widgets.Padding
import com.github.ajalt.mordant.widgets.Panel
import com.github.ajalt.mordant.widgets.Text
import com.github.ajalt.mordant.widgets.Viewport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import platform.posix.log
import top.kagg886.milky.console.commands.TerminalCommand

/**
 * Owns the interactive terminal panel and its complete lifecycle.
 */
class Terminal(
    private val terminal: MordantTerminal,
    private val scope: CoroutineScope,
) {
    private val input = StringBuilder()
    private val logs = mutableListOf<ConsoleLogEntry>()
    private val commandSystem = CommandSystem(terminal)

    private var cursor = 0
    private var logScrollUp = 0
    private var running = false
    private var terminalJob: Job? = null

    fun setCommands(vararg commands: SuspendingCliktCommand) {
        check(terminalJob == null) { "Commands must be set before the terminal is started" }
        commands.filterIsInstance<TerminalCommand>().forEach { it.bindTerminal(this) }
        commandSystem.setCommands(*commands)
    }

    fun exit() {
        running = false
    }

    fun start() {
        check(terminalJob == null) { "Terminal has already been started" }
        running = true
        terminalJob = scope.launch { runTerminal() }
    }

    suspend fun awaitExit() {
        checkNotNull(terminalJob) { "Terminal has not been started" }.join()
    }

    private suspend fun runTerminal() {
        val rawMode = terminal.enterRawModeOrNull()
        if (rawMode == null || !terminal.terminalInfo.outputInteractive) {
            rawMode?.close()
            terminal.println("milky-console requires an interactive terminal")
            return
        }

        val screen = terminal.animation<ConsoleUiState> { state -> renderConsole(state) }
        val writer = ConsoleLogWriter {
            logs.add(it)
            if (logs.size > 100) logs.removeFirst()
        }
        Logger.addLogWriter(writer)

        terminal.cursor.hide(showOnExit = true)
        terminal.cursor.move {
            clearScreen()
            setPosition(0, 0)
        }

        try {
            while (running) {
                screen.update(
                    ConsoleUiState(
                        input = input.toString(),
                        cursor = cursor,
                        logs = logs.toList(),
                    ),
                )

                handleKey(rawMode.readKey())
            }
        } finally {
            running = false
            Logger.mutableConfig.logWriterList = (Logger.mutableConfig.logWriterList - writer)
            rawMode.close()
            screen.clear()
            terminal.cursor.move {
                clearScreen()
                setPosition(0, 0)
            }
            terminal.cursor.show()
        }
    }

    private suspend fun handleKey(event: KeyboardEvent) {
        when {
            event.isCtrlC || event.key == "Escape" -> running = false
            event.key == "Enter" -> submitInput()
            event.key == "Backspace" && cursor > 0 -> {
                input.deleteAt(cursor - 1)
                cursor--
            }

            event.key == "Delete" && cursor < input.length -> input.deleteAt(cursor)
            event.key == "ArrowLeft" && cursor > 0 -> cursor--
            event.key == "ArrowRight" && cursor < input.length -> cursor++
            event.key == "ArrowUp" -> logScrollUp++
            event.key == "ArrowDown" && logScrollUp > 0 -> logScrollUp--
            event.key == "Home" -> cursor = 0
            event.key == "End" -> cursor = input.length
            event.isPrintable -> {
                input.insert(cursor, event.key)
                cursor += event.key.length
            }
        }
    }

    private suspend fun submitInput() {
        val command = input.toString().trim()
        input.clear()
        cursor = 0

        if (command.isNotEmpty()) {
            commandSystem.execute(command)
        }
    }

    private fun renderConsole(state: ConsoleUiState) = verticalLayout {
        val terminalWidth = terminal.size.width.coerceAtLeast(12)
        val contentWidth = (terminalWidth - 2).coerceAtLeast(1)
        val logHeight = (terminal.size.height - 5).coerceAtLeast(1)
        val logText = state.logs.joinToString("\n", transform = ::renderLog)
        val logLineCount = if (logText.isEmpty()) 0 else logText.count { it == '\n' } + 1
        val maxLogScroll = (logLineCount - logHeight).coerceAtLeast(0)
        logScrollUp = logScrollUp.coerceAtMost(maxLogScroll)

        width = ColumnWidth.Expand()
        spacing = 0
        cell(
            Panel(
                content = Viewport(
                    content = Text(logText),
                    width = contentWidth,
                    height = logHeight,
                    scrollDown = maxLogScroll - logScrollUp,
                ),
                expand = true,
                padding = Padding(0),
                borderStyle = brightCyan + bold,
            ),
        )
        cell(
            Panel(
                content = Viewport(
                    content = Text(renderInput(state.input, state.cursor)),
                    width = contentWidth,
                    height = 1,
                    scrollRight = inputScrollOffset(state.cursor, contentWidth),
                ),
                expand = true,
                padding = Padding(0),
                borderStyle = brightCyan + bold,
            ),
        )
    }
}

private data class ConsoleUiState(
    val input: String,
    val cursor: Int,
    val logs: List<ConsoleLogEntry>,
)

private data class ConsoleLogEntry(
    val severity: Severity,
    val tag: String,
    val message: String,
    val throwable: Throwable?,
)

private class ConsoleLogWriter(
    private val onLog: (ConsoleLogEntry) -> Unit,
) : LogWriter() {
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        onLog(ConsoleLogEntry(severity, tag, message, throwable))
    }
}

private fun renderLog(log: ConsoleLogEntry): String {
    val prefix = "[${log.severity.name.uppercase()}] [${log.tag}] "
    val content = buildString {
        append(log.message)
        log.throwable?.let {
            append('\n')
            append(it.stackTraceToString())
        }
    }
    return content.lineSequence().joinToString("\n") { line ->
        prefix + line
    }
}

private fun renderInput(input: String, cursor: Int): String {
    val beforeCursor = input.substring(0, cursor)
    val cursorText = input.getOrNull(cursor)?.toString() ?: " "
    val afterCursor = input.substring((cursor + 1).coerceAtMost(input.length))
    return "${brightCyan("› ")}$beforeCursor${inverse(cursorText)}$afterCursor"
}

private fun inputScrollOffset(cursor: Int, contentWidth: Int): Int {
    val promptWidth = 2
    val availableInputWidth = (contentWidth - promptWidth).coerceAtLeast(1)
    return (cursor - availableInputWidth + 1).coerceAtLeast(0)
}

private val KeyboardEvent.isPrintable: Boolean
    get() = !ctrl && !alt && key.isNotEmpty() && key.none { it.isISOControl() } &&
            key !in NON_PRINTABLE_KEYS

private val NON_PRINTABLE_KEYS = setOf(
    "ArrowDown",
    "ArrowLeft",
    "ArrowRight",
    "ArrowUp",
    "Backspace",
    "Delete",
    "End",
    "Enter",
    "Escape",
    "Home",
    "Insert",
    "PageDown",
    "PageUp",
    "Tab",
    "Unidentified",
)
