package top.kagg886.milky.console.util.logger

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/14 15:55
 * ================================================
 */

val String.asTaggedLogger
    get() = Logger.withTag(this)

fun Logger.log(severity: Severity, throwable: Throwable?, message: String) {
    if (config.minSeverity <= severity) {
        processLog(
            severity,
            tag,
            throwable,
            message,
        )
    }
}
