package top.kagg886.milky.console.util

/**
 * ================================================
 * Author:     886kagg
 * Created on: 2026/7/15 21:29
 * ================================================
 */

import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select

suspend fun <T> raceN(vararg tasks: suspend () -> T): T = coroutineScope {
    val tasks = tasks.map { task ->
        async(start = CoroutineStart.LAZY) {
            task()
        }
    }

    try {
        tasks.forEach { it.start() }

        select {
            tasks.forEach { deferred ->
                deferred.onAwait { result ->
                    result
                }
            }
        }
    } finally {
        // 取消所有未完成任务
        tasks.forEach { deferred ->
            if (!deferred.isCompleted) {
                deferred.cancel()
            }
        }
    }
}
