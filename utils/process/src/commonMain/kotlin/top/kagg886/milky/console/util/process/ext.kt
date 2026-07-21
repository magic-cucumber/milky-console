package top.kagg886.milky.console.util.process

import okio.Sink
import okio.Source

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/15 14:57
 * ================================================
 */


val Process.supportRedirectStdIn
    get() = (this is InheritedStdIn).also {
        logger.v { "checked stdin redirection support: pid=$pid, supported=$it" }
    }

val Process.supportRedirectStdOut
    get() = (this is InheritedStdOut).also {
        logger.v { "checked stdout redirection support: pid=$pid, supported=$it" }
    }

val Process.supportRedirectStdErr
    get() = (this is InheritedStdErr).also {
        logger.v { "checked stderr redirection support: pid=$pid, supported=$it" }
    }

val Process.stdin: Sink
    get() {
        logger.v { "enter stdin getter: pid=$pid" }
        require(supportRedirectStdIn) {
            logger.w { "stdin getter unsupported for process: pid=$pid" }
            "getting stdin is not supported by this process"
        }
        val sink = (this as InheritedStdIn).stdin
        logger.d { "resolved stdin sink: pid=$pid, expected=true" }
        logger.v { "exit stdin getter: pid=$pid" }
        return sink
    }

val Process.stdout: Source
    get() {
        logger.v { "enter stdout getter: pid=$pid" }
        require(supportRedirectStdOut) {
            logger.w { "stdout getter unsupported for process: pid=$pid" }
            "getting stdout is not supported by this process"
        }
        val source = (this as InheritedStdOut).stdout
        logger.d { "resolved stdout source: pid=$pid, expected=true" }
        logger.v { "exit stdout getter: pid=$pid" }
        return source
    }

val Process.stderr: Source
    get() {
        logger.v { "enter stderr getter: pid=$pid" }
        require(supportRedirectStdErr) {
            logger.w { "stderr getter unsupported for process: pid=$pid" }
            "getting stderr is not supported by this process"
        }
        val source = (this as InheritedStdErr).stderr
        logger.d { "resolved stderr source: pid=$pid, expected=true" }
        logger.v { "exit stderr getter: pid=$pid" }
        return source
    }
