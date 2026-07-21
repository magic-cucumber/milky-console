package top.kagg886.milky.console.util.eventbus

import co.touchlab.kermit.Logger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
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
    private const val subscriberBufferCapacity = 64

    internal class Subscriber {
        val channel = Channel<Any>(subscriberBufferCapacity)
        val availableSlots = Semaphore(subscriberBufferCapacity)
        val gate = Mutex()
    }

    private val channelsMutex = Mutex()
    /** Serializes broadcasts, preserving one global delivery order. */
    private val deliveryMutex = Mutex()
    internal val channels = mutableMapOf<KClass<*>, MutableSet<Subscriber>>()

    suspend fun post(event: Any) {
        logger.v { "enter post: eventType=${event::class.simpleName}" }
        deliveryMutex.withLock {
            val subscribers = snapshotSubscribers(event)
            logger.i { "posting event: eventType=${event::class.simpleName}, subscriberCount=${subscribers.size}" }
            subscribers.forEach { subscriber ->
                try {
                    subscriber.availableSlots.acquire()
                    logger.v { "post sending event to subscriber: eventType=${event::class.simpleName}" }
                    subscriber.channel.send(event)
                    logger.d { "post sent event to subscriber: eventType=${event::class.simpleName}, expected=true" }
                } catch (_: ClosedSendChannelException) {
                    subscriber.availableSlots.release()
                    logger.w { "post skipped closed subscriber channel: eventType=${event::class.simpleName}" }
                    // The subscriber was cancelled after the snapshot was taken.
                }
            }
        }
        logger.v { "exit post successfully: eventType=${event::class.simpleName}" }
    }

    fun tryPost(event: Any): Boolean {
        logger.v { "enter tryPost: eventType=${event::class.simpleName}" }
        if (!deliveryMutex.tryLock()) {
            logger.w { "exit tryPost unsuccessfully: delivery busy, eventType=${event::class.simpleName}" }
            return false
        }

        return try {
            val subscribers = snapshotSubscribersTry(event) ?: return false
            dispatchAtomically(event, subscribers)
        } finally {
            deliveryMutex.unlock()
        }
    }

    /**
     * Delivers from a blocking/native callback boundary without suspending.
     * The whole fan-out is serialized; delivery never waits for collectors to run.
     */
    fun postBlocking(event: Any): Boolean {
        logger.v { "enter postBlocking: eventType=${event::class.simpleName}" }
        while (!deliveryMutex.tryLock()) {
            logger.v { "postBlocking waiting for delivery mutex: eventType=${event::class.simpleName}" }
            // Native callback and pipe threads cannot call the suspending post().
        }
        return try {
            while (!channelsMutex.tryLock()) {
                logger.v { "postBlocking waiting for channels mutex: eventType=${event::class.simpleName}" }
            }
            val subscribers = try {
                snapshotSubscribersLocked(event)
            } finally {
                channelsMutex.unlock()
            }
            dispatchAtomically(event, subscribers)
        } finally {
            deliveryMutex.unlock()
        }
    }

    /**
     * Reserves one buffer slot in every subscriber before sending to any of them.
     * A failed reservation therefore leaves every channel unchanged.
     */
    private fun dispatchAtomically(event: Any, subscribers: List<Subscriber>): Boolean {
        if (subscribers.isEmpty()) return false

        val locked = ArrayList<Subscriber>(subscribers.size)
        val reserved = ArrayList<Subscriber>(subscribers.size)
        try {
            for (subscriber in subscribers) {
                if (!subscriber.gate.tryLock()) return false
                locked += subscriber
            }
            for (subscriber in subscribers) {
                if (!subscriber.availableSlots.tryAcquire()) return false
                reserved += subscriber
            }
            subscribers.forEach { subscriber ->
                check(subscriber.channel.trySend(event).isSuccess) {
                    "Reserved subscriber channel rejected an event"
                }
            }
            return true
        } finally {
            if (reserved.size != subscribers.size) {
                reserved.forEach { it.availableSlots.release() }
            }
            locked.asReversed().forEach { it.gate.unlock() }
        }
    }

    private suspend fun snapshotSubscribers(event: Any): List<Subscriber> = channelsMutex.withLock {
        snapshotSubscribersLocked(event)
    }

    private fun snapshotSubscribersTry(event: Any): List<Subscriber>? {
        if (!channelsMutex.tryLock()) return null
        return try {
            snapshotSubscribersLocked(event)
        } finally {
            channelsMutex.unlock()
        }
    }

    private fun snapshotSubscribersLocked(event: Any): List<Subscriber> {
        val matchedTypes = channels.keys.filter { it.isInstance(event) }
        logger.d { "matched subscriber types: eventType=${event::class.simpleName}, typeCount=${matchedTypes.size}" }
        return matchedTypes.flatMap { channels[it]?.toList().orEmpty() }
    }

    fun <T : Any> subscribe(
        type: KClass<T>,
        onSubscribed: (() -> Unit)? = null,
    ): Flow<T> = flow {
        logger.v { "enter subscribe flow: type=${type.simpleName}, hasCallback=${onSubscribed != null}" }
        val subscriber = Subscriber()
        logger.d { "subscription channel created: type=${type.simpleName}, expected=true" }

        channelsMutex.withLock {
            logger.v { "subscribe acquired channels mutex: type=${type.simpleName}" }
            val subscribers = channels.getOrPut(type) { mutableSetOf() }
            subscribers.add(subscriber)
            logger.i { "subscribed event listener: type=${type.simpleName}, subscriberCount=${subscribers.size}" }
        }
        logger.v { "subscribe invoking onSubscribed callback: type=${type.simpleName}, present=${onSubscribed != null}" }
        onSubscribed?.invoke()
        logger.d { "subscribe callback completed: type=${type.simpleName}, expected=true" }

        try {
            logger.v { "subscribe collecting channel flow: type=${type.simpleName}" }
            for (event in subscriber.channel) {
                subscriber.availableSlots.release()
                @Suppress("UNCHECKED_CAST")
                emit(event as T)
            }
            logger.v { "subscribe collection completed normally: type=${type.simpleName}" }
        } finally {
            logger.v { "subscribe entering cleanup: type=${type.simpleName}" }
            channelsMutex.withLock {
                logger.v { "subscribe cleanup acquired channels mutex: type=${type.simpleName}" }
                channels[type]?.let { subscribers ->
                    subscribers.remove(subscriber)
                    logger.i { "unsubscribed event listener: type=${type.simpleName}, subscriberCount=${subscribers.size}" }
                    if (subscribers.isEmpty()) channels.remove(type)
                    logger.d { "subscription cleanup result: type=${type.simpleName}, removedEmptyType=${subscribers.isEmpty()}" }
                }
            }
            subscriber.gate.withLock { subscriber.channel.close() }
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
