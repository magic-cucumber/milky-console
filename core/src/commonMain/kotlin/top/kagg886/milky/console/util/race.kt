package top.kagg886.milky.console.util

import co.touchlab.kermit.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select



suspend fun <T> raceN(vararg tasks: suspend () -> T): T = coroutineScope {
    
    
    val deferredTasks = tasks.map { task ->
        async(start = CoroutineStart.LAZY) {
            task()
        }
    }
    

    try {
        
        deferredTasks.forEach { it.start() }

        
        val result = select<T> {
            deferredTasks.forEach { deferred ->
                deferred.onAwait { result ->
                    
                    result
                }
            }
        }
        
        
        result
    } finally {
        
        deferredTasks.forEach { deferred ->
            if (!deferred.isCompleted) {
                deferred.cancel()
                
            }
        }
        
    }
}
