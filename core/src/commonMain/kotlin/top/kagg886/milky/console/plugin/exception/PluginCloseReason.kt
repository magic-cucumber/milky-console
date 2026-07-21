package top.kagg886.milky.console.plugin.exception

import top.kagg886.milky.console.util.process.Process

sealed interface PluginCloseReason {
    val exception: Throwable?
    val shouldReload: Boolean

    data class VerificationFailed(
        override val exception: PluginException,
    ) : PluginCloseReason {
        override val shouldReload = false
    }

    data class HandshakeFailed(
        override val exception: PluginhandshakeFailedException,
    ) : PluginCloseReason {
        override val shouldReload = true
    }

    data class HostRequested(
        val reason: String,
        val exitStatus: Process.ExitStatus,
    ) : PluginCloseReason {
        override val exception: Throwable? = exitStatus.toExceptionOrNull()
        override val shouldReload = false
    }

    data class PluginRequested(
        val message: String,
        val stacktrace: String?,
        val exitStatus: Process.ExitStatus,
    ) : PluginCloseReason {
        override val exception = PluginReportedCloseException(message, stacktrace)
        override val shouldReload = true
    }

    data class ProcessExited(
        val exitStatus: Process.ExitStatus,
    ) : PluginCloseReason {
        override val exception: Throwable? = exitStatus.toExceptionOrNull()
        override val shouldReload = exception != null
    }
}

private fun Process.ExitStatus.toExceptionOrNull(): PluginProcessExitException? = when (this) {
    Process.ExitStatus.Killed -> PluginProcessExitException(this)
    is Process.ExitStatus.Result -> if (exitCode == 0) null else PluginProcessExitException(this)
}
