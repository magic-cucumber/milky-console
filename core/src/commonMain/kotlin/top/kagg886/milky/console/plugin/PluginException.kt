package top.kagg886.milky.console.plugin

import okio.IOException

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/15 17:30
 * ================================================
 */
class PluginException(override val message: String? = null, override val cause: Throwable? = null) :
    IOException(message = message, cause = cause)
