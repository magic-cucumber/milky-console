package top.kagg886.milky.console.util.eventbus

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
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
    internal val channels = mutableMapOf<KClass<*>, MutableSet<Channel<Any>>>()

    suspend fun post(event: Any) {
        val subscribers = channelsMutex.withLock {
            channels.keys.filter { it.isInstance(event) }.map { channels[it]?.toList().orEmpty() }.flatten()
        }

        subscribers.forEach { channel ->
            try {
                channel.send(event)
            } catch (_: ClosedSendChannelException) {
                // The subscriber was cancelled after the snapshot was taken.
            }
        }
    }

    fun tryPost(event: Any): Boolean {
        if (!channelsMutex.tryLock()) return false

        val subscribers = try {
            channels.keys.filter { it.isInstance(event) }.map { channels[it]?.toList().orEmpty() }.flatten()
        } finally {
            channelsMutex.unlock()
        }

        return subscribers.isNotEmpty() && subscribers.all { channel ->
            channel.trySend(event).isSuccess
        }
    }

    /**
     * Delivers from a blocking/native callback boundary without suspending.
     * Subscriber channels are buffered, so only the short registry snapshot is
     * serialized here; delivery never waits for collectors to run.
     */
    fun postBlocking(event: Any): Boolean {
        while (!channelsMutex.tryLock()) {
            // Native callback and pipe threads cannot call the suspending post().
        }
        val subscribers = try {
            channels.keys.filter { it.isInstance(event) }.flatMap { channels[it]?.toList().orEmpty() }
        } finally {
            channelsMutex.unlock()
        }
        return subscribers.all { channel -> channel.trySend(event).isSuccess }
    }

    fun <T : Any> subscribe(type: KClass<T>): Flow<T> = flow {
        val channel = Channel<Any>(Channel.BUFFERED)

        channelsMutex.withLock {
            channels.getOrPut(type) { mutableSetOf() }.add(channel)
        }

        try {
            channel.receiveAsFlow().filterIsInstance(type).collect(::emit)
        } finally {
            channelsMutex.withLock {
                channels[type]?.let { subscribers ->
                    subscribers.remove(channel)
                    if (subscribers.isEmpty()) channels.remove(type)
                }
            }
            channel.close()
        }
    }

    inline fun <reified T : Any> subscribe(): Flow<T> = subscribe(T::class)
}
