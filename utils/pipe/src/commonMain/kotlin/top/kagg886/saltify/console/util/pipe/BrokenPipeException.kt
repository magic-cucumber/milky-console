package top.kagg886.saltify.console.util.pipe

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/14 10:48
 * ================================================
 */

class BrokenPipeException(override val message: String?, override val cause: Throwable?) :
    okio.IOException(message, cause)
