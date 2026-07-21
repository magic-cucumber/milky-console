package top.kagg886.milky.console.plugin

import co.touchlab.kermit.Logger
import okio.IOException
import top.kagg886.milky.console.protocol.PluginHandshakeError



open class PluginException(override val message: String? = null, override val cause: Throwable? = null) :
    IOException(message = message, cause = cause) {
    init {
    }
}

class PluginhandshakeFailedException(
    override val message: String,
    val error: PluginHandshakeError? = null,
    override val cause: Throwable? = null,
) : PluginException(message, cause)

class PluginReportedCloseException(
    val pluginMessage: String,
    val pluginStacktrace: String?,
) : PluginException(
    buildString {
        append("plugin closed unexpectedly: ")
        append(pluginMessage)
        if (!pluginStacktrace.isNullOrBlank()) append("\n").append(pluginStacktrace)
    },
)

class PluginProcessExitException(
    val exitStatus: top.kagg886.milky.console.util.process.Process.ExitStatus,
) : PluginException("plugin process exited: $exitStatus")
