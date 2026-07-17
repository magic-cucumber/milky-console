import co.touchlab.kermit.Logger



/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/16 10:50
 * ================================================
 */

fun List<Int>.isContinuous(): Boolean {
    
    val result = size <= 1 || zipWithNext().all { (a, b) -> b == a + 1 }
    
    
    return result
}
