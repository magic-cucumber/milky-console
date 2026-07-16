package top.kagg886.milky.console.plugin

import co.touchlab.kermit.Logger
import okio.IOException

private val log = Logger.withTag("PluginException")

class PluginException(override val message: String? = null, override val cause: Throwable? = null) :
    IOException(message = message, cause = cause) {
    init {
        log.e { "PluginException created: message=$message, cause=${cause?.message}" }
    }
}
