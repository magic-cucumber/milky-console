package top.kagg886.milky.console.util.process

import co.touchlab.kermit.Logger
import okio.Sink
import okio.Source

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/15 13:58
 * ================================================
 */

internal val logger = Logger.withTag("Process")

interface Process {
    val pid: Long

    suspend fun await(): ExitStatus
    fun shutdown(): Boolean
    fun kill(): Boolean

    sealed interface ExitStatus {
        data object Killed : ExitStatus
        data class Result(val exitCode: Int) : ExitStatus
    }

    companion object
}

interface InheritedStdIn {
    val stdin: Sink
}

interface InheritedStdOut {
    val stdout: Source
}

interface InheritedStdErr {
    val stderr: Source
}


fun Process.Companion.create(builder: @ProcessDslMarker ProcessBuilderScope.() -> Unit): Process {
    logger.v { "enter create with builder" }
    val config = ProcessBuilderScope().apply(builder).build()
    logger.d { "builder produced process config: executable=${config.executable}, arguments=${config.arguments.size}, environment=${config.environment.size}, stdin=${config.stdin}, stdout=${config.stdout}, stderr=${config.stderr}, inheritedFD=${config.inheritedFD.size}" }
    val process = create(config)
    logger.v { "exit create with builder: pid=${process.pid}" }
    return process
}
expect fun Process.Companion.create(config: ProcessConfig): Process
