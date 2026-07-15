package top.kagg886.milky.console.util.pipe

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/14 10:48
 * ================================================
 */

class BrokenPipeException(override val message: String?, override val cause: Throwable? = null) :
    okio.IOException(message, cause)
