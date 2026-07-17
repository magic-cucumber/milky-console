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
