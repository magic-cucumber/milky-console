package top.kagg886.milky.console.plugin.exception

import co.touchlab.kermit.Logger
import top.kagg886.milky.console.util.process.Process

private val logger = Logger.withTag("PluginCloseReason")

sealed interface PluginCloseReason {
    val exception: Throwable?
    val shouldReload: Boolean

    data class VerificationFailed(
        override val exception: PluginException,
    ) : PluginCloseReason {
        override val shouldReload = false
        init {
            logger.e(exception) { "close reason created: verification failed, shouldReload=$shouldReload" }
        }
    }

    data class HandshakeFailed(
        override val exception: PluginhandshakeFailedException,
    ) : PluginCloseReason {
        override val shouldReload = true
        init {
            logger.e(exception) { "close reason created: handshake failed, shouldReload=$shouldReload" }
        }
    }

    data class HostRequested(
        val reason: String,
        val exitStatus: Process.ExitStatus,
    ) : PluginCloseReason {
        override val exception: Throwable? = exitStatus.toExceptionOrNull()
        override val shouldReload = false
        init {
            logger.i { "close reason created: host requested, reason=$reason, exitStatus=$exitStatus, shouldReload=$shouldReload" }
        }
    }

    data class PluginRequested(
        val message: String,
        val stacktrace: String?,
        val exitStatus: Process.ExitStatus,
    ) : PluginCloseReason {
        override val exception = PluginReportedCloseException(message, stacktrace)
        override val shouldReload = true
        init {
            logger.w(exception) { "close reason created: plugin requested, message=$message, shouldReload=$shouldReload" }
        }
    }

    data class ProcessExited(
        val exitStatus: Process.ExitStatus,
    ) : PluginCloseReason {
        override val exception: Throwable? = exitStatus.toExceptionOrNull()
        override val shouldReload = exception != null
        init {
            logger.w(exception) { "close reason created: process exited, exitStatus=$exitStatus, shouldReload=$shouldReload" }
        }
    }
}

private fun Process.ExitStatus.toExceptionOrNull(): PluginProcessExitException? = when (this) {
    Process.ExitStatus.Killed -> {
        logger.w { "process exit status branch: killed" }
        PluginProcessExitException(this)
    }
    is Process.ExitStatus.Result -> if (exitCode == 0) {
        logger.v { "process exit status branch: success exitCode=$exitCode" }
        null
    } else {
        logger.w { "process exit status branch: non-zero exitCode=$exitCode" }
        PluginProcessExitException(this)
    }
}
