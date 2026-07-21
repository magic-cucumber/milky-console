package top.kagg886.milky.console.util.eventbus

import co.touchlab.kermit.Logger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.reflect.KClass

private val logger = Logger.withTag("EventBus")

@PublishedApi
internal fun logReifiedSubscribeEnter(typeName: String?, hasCallback: Boolean) {
    logger.v { "enter reified subscribe: type=$typeName, hasCallback=$hasCallback" }
}

@PublishedApi
internal fun logReifiedSubscribeCreated(typeName: String?) {
    logger.d { "reified subscribe created flow: type=$typeName, expected=true" }
}

@PublishedApi
internal fun logReifiedSubscribeExit(typeName: String?) {
    logger.v { "exit reified subscribe: type=$typeName" }
}

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
        logger.v { "enter post: eventType=${event::class.simpleName}" }
        val subscribers = channelsMutex.withLock {
            logger.v { "post acquired channels mutex: registeredTypes=${channels.size}" }
            val matchedTypes = channels.keys.filter { it.isInstance(event) }
            logger.d { "post matched subscriber types: eventType=${event::class.simpleName}, typeCount=${matchedTypes.size}" }
            matchedTypes.flatMap { channels[it]?.toList().orEmpty() }
        }
        logger.i { "posting event: eventType=${event::class.simpleName}, subscriberCount=${subscribers.size}" }
        subscribers.forEach { channel ->
            try {
                logger.v { "post sending event to subscriber: eventType=${event::class.simpleName}" }
                channel.send(event)
                logger.d { "post sent event to subscriber: eventType=${event::class.simpleName}, expected=true" }
            } catch (_: ClosedSendChannelException) {
                logger.w { "post skipped closed subscriber channel: eventType=${event::class.simpleName}" }
                // The subscriber was cancelled after the snapshot was taken.
            }
        }
        logger.v { "exit post successfully: eventType=${event::class.simpleName}, subscriberCount=${subscribers.size}" }
    }

    fun tryPost(event: Any): Boolean {
        logger.v { "enter tryPost: eventType=${event::class.simpleName}" }
        if (!channelsMutex.tryLock()) {
            logger.w { "exit tryPost unsuccessfully: mutex busy, eventType=${event::class.simpleName}" }
            return false
        }

        val subscribers = try {
            logger.v { "tryPost acquired channels mutex: registeredTypes=${channels.size}" }
            val matchedTypes = channels.keys.filter { it.isInstance(event) }
            logger.d { "tryPost matched subscriber types: eventType=${event::class.simpleName}, typeCount=${matchedTypes.size}" }
            matchedTypes.flatMap { channels[it]?.toList().orEmpty() }
        } finally {
            channelsMutex.unlock()
            logger.v { "tryPost released channels mutex: eventType=${event::class.simpleName}" }
        }

        logger.i { "trying event post: eventType=${event::class.simpleName}, subscriberCount=${subscribers.size}" }
        val result = subscribers.isNotEmpty() && subscribers.all { channel ->
            logger.v { "tryPost sending event to subscriber: eventType=${event::class.simpleName}" }
            channel.trySend(event).isSuccess
        }
        if (subscribers.isEmpty()) {
            logger.w { "tryPost found no subscribers: eventType=${event::class.simpleName}" }
        }
        logger.d { "tryPost delivery result: eventType=${event::class.simpleName}, success=$result, expected=${subscribers.isNotEmpty()}" }
        logger.v { "exit tryPost: eventType=${event::class.simpleName}, result=$result" }
        return result
    }

    /**
     * Delivers from a blocking/native callback boundary without suspending.
     * Subscriber channels are buffered, so only the short registry snapshot is
     * serialized here; delivery never waits for collectors to run.
     */
    fun postBlocking(event: Any): Boolean {
        logger.v { "enter postBlocking: eventType=${event::class.simpleName}" }
        while (!channelsMutex.tryLock()) {
            logger.v { "postBlocking waiting for channels mutex: eventType=${event::class.simpleName}" }
            // Native callback and pipe threads cannot call the suspending post().
        }
        val subscribers = try {
            logger.v { "postBlocking acquired channels mutex: registeredTypes=${channels.size}" }
            val matchedTypes = channels.keys.filter { it.isInstance(event) }
            logger.d { "postBlocking matched subscriber types: eventType=${event::class.simpleName}, typeCount=${matchedTypes.size}" }
            matchedTypes.flatMap { channels[it]?.toList().orEmpty() }
        } finally {
            channelsMutex.unlock()
            logger.v { "postBlocking released channels mutex: eventType=${event::class.simpleName}" }
        }
        logger.i { "blocking event post: eventType=${event::class.simpleName}, subscriberCount=${subscribers.size}" }
        val result = subscribers.all { channel ->
            logger.v { "postBlocking sending event to subscriber: eventType=${event::class.simpleName}" }
            channel.trySend(event).isSuccess
        }
        logger.d { "postBlocking delivery result: eventType=${event::class.simpleName}, success=$result, expected=true" }
        logger.v { "exit postBlocking: eventType=${event::class.simpleName}, result=$result" }
        return result
    }

    fun <T : Any> subscribe(
        type: KClass<T>,
        onSubscribed: (() -> Unit)? = null,
    ): Flow<T> = flow {
        logger.v { "enter subscribe flow: type=${type.simpleName}, hasCallback=${onSubscribed != null}" }
        val channel = Channel<Any>(Channel.BUFFERED)
        logger.d { "subscription channel created: type=${type.simpleName}, expected=true" }

        channelsMutex.withLock {
            logger.v { "subscribe acquired channels mutex: type=${type.simpleName}" }
            val subscribers = channels.getOrPut(type) { mutableSetOf() }
            subscribers.add(channel)
            logger.i { "subscribed event listener: type=${type.simpleName}, subscriberCount=${subscribers.size}" }
        }
        logger.v { "subscribe invoking onSubscribed callback: type=${type.simpleName}, present=${onSubscribed != null}" }
        onSubscribed?.invoke()
        logger.d { "subscribe callback completed: type=${type.simpleName}, expected=true" }

        try {
            logger.v { "subscribe collecting channel flow: type=${type.simpleName}" }
            channel.receiveAsFlow().filterIsInstance(type).collect(::emit)
            logger.v { "subscribe collection completed normally: type=${type.simpleName}" }
        } finally {
            logger.v { "subscribe entering cleanup: type=${type.simpleName}" }
            channelsMutex.withLock {
                logger.v { "subscribe cleanup acquired channels mutex: type=${type.simpleName}" }
                channels[type]?.let { subscribers ->
                    subscribers.remove(channel)
                    logger.i { "unsubscribed event listener: type=${type.simpleName}, subscriberCount=${subscribers.size}" }
                    if (subscribers.isEmpty()) channels.remove(type)
                    logger.d { "subscription cleanup result: type=${type.simpleName}, removedEmptyType=${subscribers.isEmpty()}" }
                }
            }
            channel.close()
            logger.d { "subscription channel closed: type=${type.simpleName}, expected=true" }
            logger.v { "exit subscribe flow: type=${type.simpleName}" }
        }
    }

    inline fun <reified T : Any> subscribe(
        noinline onSubscribed: (() -> Unit)? = null,
    ): Flow<T> {
        logReifiedSubscribeEnter(T::class.simpleName, onSubscribed != null)
        val flow = subscribe(T::class, onSubscribed)
        logReifiedSubscribeCreated(T::class.simpleName)
        logReifiedSubscribeExit(T::class.simpleName)
        return flow
    }
}
