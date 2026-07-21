package top.kagg886.milky.console.util

import co.touchlab.kermit.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select

private val logger = Logger.withTag("raceN")


suspend fun <T> raceN(vararg tasks: suspend () -> T): T = coroutineScope {
    logger.i { "enter raceN: taskCount=${tasks.size}, callerActive=$isActive" }
    val deferredTasks = tasks.map { task ->
        async(start = CoroutineStart.LAZY) {
            logger.v { "enter race task" }
            task()
        }
    }

    try {
        deferredTasks.forEach { it.start() }
        logger.d { "all race tasks started: count=${deferredTasks.size}" }

        val result = select<T> {
            deferredTasks.forEach { deferred ->
                deferred.onAwait { result ->
                    logger.v { "race task selected: completed=${deferred.isCompleted}" }
                    result
                }
            }
        }
        
        logger.i { "exit raceN successfully: winner completed" }
        result
    } finally {
        deferredTasks.forEach { deferred ->
            if (!deferred.isCompleted) {
                deferred.cancel()
                logger.v { "cancelled losing race task" }
            } else {
                logger.v { "race task already completed during cleanup" }
            }
        }
        logger.d { "raceN cleanup completed" }
    }
}
