package top.kagg886.milky.console.util.process

import okio.Sink
import okio.Source

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/15 13:58
 * ================================================
 */

interface Process {
    val pid: Long

    suspend fun await(): ExitStatus
    fun shutdown(): Boolean
    fun kill(): Boolean

    sealed interface ExitStatus {
        /**
         * The process was terminated by a signal or forcibly terminated by this API.
         *
         * On Unix, [signal] is the terminating signal reported by `waitpid` (for
         * example, `SIGKILL` is 9). Windows does not have POSIX signals, so a
         * process terminated through [Process.kill] or [Process.shutdown] reports
         * `null` here.
         */
        data class Killed(val signal: Int?) : ExitStatus
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


fun Process.Companion.create(builder: @ProcessDslMarker ProcessBuilderScope.() -> Unit) : Process = create(ProcessBuilderScope().apply(builder).build())
expect fun Process.Companion.create(config: ProcessConfig): Process
