import co.touchlab.kermit.Logger

private val logger = Logger.withTag("PluginLoaderUtils")


/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/16 10:50
 * ================================================
 */

fun List<Int>.isContinuous(): Boolean {
    logger.i { "enter isContinuous: size=$size, values=$this" }
    val result = if (size <= 1) {
        logger.v { "isContinuous entered trivial branch: size=$size" }
        true
    } else {
        logger.v { "isContinuous entered pair comparison branch: size=$size" }
        zipWithNext().all { (a, b) ->
            val continuous = b == a + 1
            logger.v { "isContinuous compared pair: left=$a, right=$b, continuous=$continuous" }
            continuous
        }
    }
    logger.d { "evaluated continuity: result=$result, expected=${size <= 1 || result}" }
    logger.i { "exit isContinuous successfully: result=$result" }
    return result
}
