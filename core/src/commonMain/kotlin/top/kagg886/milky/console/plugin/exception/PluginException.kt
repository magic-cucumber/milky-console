package top.kagg886.milky.console.plugin.exception

import co.touchlab.kermit.Logger
import okio.IOException
import top.kagg886.milky.console.protocol.PluginHandshakeError
import top.kagg886.milky.console.util.process.Process

private val logger = Logger.withTag("PluginException")

open class PluginException(override val message: String? = null, override val cause: Throwable? = null) :
    IOException(message = message, cause = cause) {
    init {
        logger.w(cause) { "plugin exception created: type=${this::class.simpleName}, message=$message" }
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
) {
    init {
        logger.w { "plugin reported close exception created: message=$pluginMessage, hasStacktrace=${!pluginStacktrace.isNullOrBlank()}" }
    }
}

class PluginProcessExitException(
    val exitStatus: Process.ExitStatus,
) : PluginException("plugin process exited: $exitStatus") {
    init {
        logger.w { "plugin process exit exception created: status=$exitStatus" }
    }
}
