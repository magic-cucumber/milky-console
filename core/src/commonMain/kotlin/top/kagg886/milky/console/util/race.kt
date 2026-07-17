package top.kagg886.milky.console.util

import co.touchlab.kermit.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select

private val raceLogger = Logger.withTag("raceN")


suspend fun <T> raceN(vararg tasks: suspend () -> T): T = coroutineScope {
    raceLogger.i { "enter raceN: taskCount=${tasks.size}, callerActive=$isActive" }
    val deferredTasks = tasks.map { task ->
        async(start = CoroutineStart.LAZY) {
            task()
        }
    }

    try {
        deferredTasks.forEach { it.start() }
        raceLogger.d { "all race tasks started: count=${deferredTasks.size}" }

        val result = select<T> {
            deferredTasks.forEach { deferred ->
                deferred.onAwait { result ->
                    result
                }
            }
        }
        
        raceLogger.i { "exit raceN successfully: winner completed" }
        result
    } finally {
        deferredTasks.forEach { deferred ->
            if (!deferred.isCompleted) {
                deferred.cancel()
                raceLogger.v { "cancelled losing race task" }
            }
        }
        raceLogger.d { "raceN cleanup completed" }
    }
}
