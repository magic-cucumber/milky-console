import co.touchlab.kermit.Logger

private val pluginLoaderUtilsLogger = Logger.withTag("PluginLoaderUtils")


/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/16 10:50
 * ================================================
 */

fun List<Int>.isContinuous(): Boolean {
    pluginLoaderUtilsLogger.i { "enter isContinuous: size=$size, values=$this" }
    val result = size <= 1 || zipWithNext().all { (a, b) -> b == a + 1 }
    pluginLoaderUtilsLogger.d { "evaluated continuity: result=$result, expected=${size <= 1 || result}" }
    pluginLoaderUtilsLogger.i { "exit isContinuous successfully: result=$result" }
    return result
}
