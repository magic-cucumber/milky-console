import co.touchlab.kermit.Logger

private val log = Logger.withTag("Utils")

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/16 10:50
 * ================================================
 */

fun List<Int>.isContinuous(): Boolean {
    log.v { ">>> List<Int>.isContinuous() enter, size=$size" }
    val result = size <= 1 || zipWithNext().all { (a, b) -> b == a + 1 }
    log.v { "isContinuous: list=$this, result=$result" }
    log.v { "<<< List<Int>.isContinuous() exit" }
    return result
}
