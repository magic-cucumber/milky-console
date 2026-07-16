package top.kagg886.milky.console.util

import co.touchlab.kermit.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select

private val log = Logger.withTag("Race")

suspend fun <T> raceN(vararg tasks: suspend () -> T): T = coroutineScope {
    log.i { ">>> raceN() enter, taskCount=${tasks.size}" }
    log.v { "raceN: creating LAZY async wrappers for ${tasks.size} tasks" }
    val deferredTasks = tasks.map { task ->
        async(start = CoroutineStart.LAZY) {
            task()
        }
    }
    log.d { "[group: task-init] ${deferredTasks.size} async tasks created" }

    try {
        log.v { "raceN: starting all tasks" }
        deferredTasks.forEach { it.start() }

        log.v { "raceN: entering select block, waiting for first completion" }
        val result = select<T> {
            deferredTasks.forEach { deferred ->
                deferred.onAwait { result ->
                    log.v { "raceN: task completed with result=$result" }
                    result
                }
            }
        }
        log.d { "[group: race-result] first task completed, result=$result" }
        log.i { "<<< raceN() exit, result=$result" }
        result
    } finally {
        log.v { "raceN: cancelling all incomplete tasks" }
        deferredTasks.forEach { deferred ->
            if (!deferred.isCompleted) {
                deferred.cancel()
                log.v { "raceN: cancelled incomplete task" }
            }
        }
        log.d { "[group: cleanup] all incomplete tasks cancelled" }
    }
}
