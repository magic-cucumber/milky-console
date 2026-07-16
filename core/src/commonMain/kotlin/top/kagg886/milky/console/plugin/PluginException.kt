package top.kagg886.milky.console.plugin

import co.touchlab.kermit.Logger
import okio.IOException
import top.kagg886.milky.console.protocol.PluginHandshakeError

private val log = Logger.withTag("PluginException")

open class PluginException(override val message: String? = null, override val cause: Throwable? = null) :
    IOException(message = message, cause = cause) {
    init {
        log.e { "PluginException created: message=$message, cause=${cause?.message}" }
    }
}

class PluginhandshakeFailedException(
    override val message: String,
    val error: PluginHandshakeError? = null,
    override val cause: Throwable? = null,
) : PluginException(message, cause)
