package top.kagg886.milky.console.util.eventbus

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.reflect.KClass

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/15 16:38
 * ================================================
 */

object EventBus {
    private val channelsMutex = Mutex()
    private val channels = mutableMapOf<KClass<*>, Channel<Any>>()

    suspend fun post(event: Any) =
        channelFor(event::class).send(event)

    fun tryPost(event: Any): Boolean {
        if (!channelsMutex.tryLock()) return false

        return try {
            channelForLocked(event::class).trySend(event).isSuccess
        } finally {
            channelsMutex.unlock()
        }
    }

    fun <T : Any> subscribe(type: KClass<T>): Flow<T> = flow {
        channelFor(type).receiveAsFlow().filterIsInstance(type).collect(::emit)
    }

    inline fun <reified T : Any> subscribe(): Flow<T> = subscribe(T::class)

    private suspend fun channelFor(type: KClass<*>): Channel<Any> = channelsMutex.withLock {
        channelForLocked(type)
    }

    private fun channelForLocked(type: KClass<*>): Channel<Any> = channels.getOrPut(type) {
        Channel(Channel.BUFFERED)
    }
}
