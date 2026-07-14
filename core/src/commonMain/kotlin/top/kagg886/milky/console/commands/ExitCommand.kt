package top.kagg886.milky.console.commands

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/14 16:57
 * ================================================
 */


class ExitCommand(name: String = "/exit") : TerminalCommand(name) {
    override suspend fun run() {
        terminal.exit()
    }
}
