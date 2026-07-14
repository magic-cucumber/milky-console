package top.kagg886.milky.console.terminal

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.test.runTest
import top.kagg886.milky.console.commands.TerminalCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CommandTest {
    @Test
    fun parsesCommandLineWithQuotesAndEscapes() {
        assertEquals(
            listOf("command", "plain", "single quoted", "double quoted", "escaped value", "double \"quoted\""),
            "command plain 'single quoted' \"double quoted\" escaped\\ value \"double \\\"quoted\\\"\"".toArgv(),
        )
    }

    @Test
    fun followsAstForEmptyQuotesAndTrailingEscape() {
        assertEquals(listOf("foobar baz", "a\\b", "tail"), "foo\"bar baz\" \"\" 'a\\b' tail\\".toArgv())
    }

    @Test
    fun dispatchesCommandsAndCollectsProgressiveEcho() = runTest {
        val commands = CommandSystem(Terminal(interactive = false)).apply {
            setCommands(EchoCommand())
        }

        assertEquals(
            listOf("first:hello world", "second"),
            commands.execute("echo \"hello world\""),
        )
        assertEquals(
            listOf("first:again", "second"),
            commands.execute("echo again"),
        )
    }

    @Test
    fun rootReusesCliktHelpAndCommandsAreNonInteractive() = runTest {
        val commands = CommandSystem(Terminal(interactive = true)).apply {
            setCommands(EchoCommand(), InteractiveStateCommand())
        }

        val help = commands.execute("--help").joinToString("\n")
        assertTrue(help.contains("echo"))
        assertTrue(help.contains("interactive-state"))
        assertEquals(listOf("false"), commands.execute("interactive-state"))
    }

    @Test
    fun terminalCommandsAreBoundToTheirOwningTerminal() = runTest {
        val owner = top.kagg886.milky.console.terminal.Terminal(
            terminal = Terminal(interactive = false),
            scope = backgroundScope,
        )
        val command = BoundTerminalCommand()

        owner.setCommands(command)
        command.run()

        assertSame(owner, command.terminalFromRun)
    }
}

private class EchoCommand : SuspendingCliktCommand(name = "echo") {
    private val value by argument()

    override suspend fun run() {
        echo("first:$value", trailingNewline = false)
        echo("second")
    }
}

private class InteractiveStateCommand : SuspendingCliktCommand(name = "interactive-state") {
    override suspend fun run() {
        assertFalse(terminal.terminalInfo.inputInteractive)
        echo(terminal.terminalInfo.inputInteractive)
    }
}

private class BoundTerminalCommand : TerminalCommand(name = "bound-terminal") {
    var terminalFromRun: top.kagg886.milky.console.terminal.Terminal? = null
        private set

    override suspend fun run() {
        terminalFromRun = terminal
    }
}
