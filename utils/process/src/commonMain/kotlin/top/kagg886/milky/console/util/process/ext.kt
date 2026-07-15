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
    get() = this is InheritedStdIn

val Process.supportRedirectStdOut
    get() = this is InheritedStdOut

val Process.supportRedirectStdErr
    get() = this is InheritedStdErr

val Process.stdin: Sink
    get() {
        require(supportRedirectStdIn) { "getting stdin is not supported by this process" }
        return (this as InheritedStdIn).stdin
    }

val Process.stdout: Source
    get() {
        require(supportRedirectStdOut) { "getting stdout is not supported by this process" }
        return (this as InheritedStdOut).stdout
    }

val Process.stderr: Source
    get() {
        require(supportRedirectStdErr) { "getting stderr is not supported by this process" }
        return (this as InheritedStdErr).stderr
    }
