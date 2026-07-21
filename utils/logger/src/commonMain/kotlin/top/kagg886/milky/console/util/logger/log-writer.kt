package top.kagg886.milky.console.util.logger

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.Severity.Assert
import co.touchlab.kermit.Severity.Debug
import co.touchlab.kermit.Severity.Info
import co.touchlab.kermit.Severity.Verbose
import co.touchlab.kermit.Severity.Warn
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * ================================================
 * Author:     kagg886
 * Created on: 2026/7/18 19:26
 * ================================================
 */


val MilkyConsoleDefaultLogWriter = object : LogWriter() {
    private val lock = reentrantLock()
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
            Severity.Error -> Triple("\u001B[37m", "\u001B[41m", "\u001B[31m")
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
                if (index > 0) appendLine()
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
