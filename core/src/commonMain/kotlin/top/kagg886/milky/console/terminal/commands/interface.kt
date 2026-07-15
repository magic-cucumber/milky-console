package top.kagg886.milky.console.terminal.commands

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import top.kagg886.milky.console.terminal.Terminal

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/14 16:57
 * ================================================
 */

abstract class TerminalCommand(name: String? = null) : SuspendingCliktCommand(name) {
    lateinit var terminal: Terminal
        private set

    internal fun bindTerminal(terminal: Terminal) {
        this.terminal = terminal
    }
}
