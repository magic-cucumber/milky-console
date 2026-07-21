package top.kagg886.milky.console.util.eventbus

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class EventBusTest {
    @Test
    fun anEventIsDeliveredToEverySameTypeSubscriber() = runBlocking {
        val event = SingleConsumerEvent(42)
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            EventBus.subscribe<SingleConsumerEvent>().first()
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            EventBus.subscribe<SingleConsumerEvent>().first()
        }

        try {
            EventBus.post(event)
            assertEquals(event, withTimeout(1.seconds) { first.await() })
            assertEquals(event, withTimeout(1.seconds) { second.await() })
        } finally {
            first.cancelAndJoin()
            second.cancelAndJoin()
        }
    }

    @Test
    fun eventTypesAreDeliveredToMatchingSubscribers() = runBlocking {
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            EventBus.subscribe<FirstEvent>().first()
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            EventBus.subscribe<SecondEvent>().first()
        }

        try {
            EventBus.post(FirstEvent("first"))
            EventBus.post(SecondEvent("second"))

            assertEquals(FirstEvent("first"), withTimeout(1.seconds) { first.await() })
            assertEquals(SecondEvent("second"), withTimeout(1.seconds) { second.await() })
        } finally {
            first.cancelAndJoin()
            second.cancelAndJoin()
        }
    }

    @Test
    fun childEventIsDeliveredToChildAndParentSubscribers() = runBlocking {
        val event = ChildEvent("child")
        val parent = async(start = CoroutineStart.UNDISPATCHED) {
            EventBus.subscribe<ParentEvent>().first()
        }
        val child = async(start = CoroutineStart.UNDISPATCHED) {
            EventBus.subscribe<ChildEvent>().first()
        }

        try {
            EventBus.post(event)

            assertEquals(event, withTimeout(1.seconds) { parent.await() })
            assertEquals(event, withTimeout(1.seconds) { child.await() })
        } finally {
            parent.cancelAndJoin()
            child.cancelAndJoin()
        }
    }

    @Test
    fun eventPostedWithoutSubscribersIsDiscarded() = runBlocking {
        EventBus.post(UndeliveredEvent("discard"))

        assertNull(withTimeoutOrNull(100.milliseconds) {
            EventBus.subscribe<UndeliveredEvent>().first()
        })
    }

    @Test
    fun cancelledSubscribersAreRemovedFromChannels() = runBlocking {
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            EventBus.subscribe<LifecycleEvent>().collect { }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            EventBus.subscribe<LifecycleEvent>().collect { }
        }

        try {
            assertEquals(2, EventBus.channels[LifecycleEvent::class]?.size)

            first.cancelAndJoin()
            assertEquals(1, EventBus.channels[LifecycleEvent::class]?.size)

            second.cancelAndJoin()
            assertNull(EventBus.channels[LifecycleEvent::class])
        } finally {
            first.cancelAndJoin()
            second.cancelAndJoin()
        }
    }

    @Test
    fun tryPostFailsWhenASubscriberChannelIsCongested() = runBlocking {
        val blockedSubscribed = CompletableDeferred<Unit>()
        val healthySubscribed = CompletableDeferred<Unit>()
        val healthyEvents = mutableListOf<Int>()

        val blockedSubscriber = async(start = CoroutineStart.UNDISPATCHED) {
            EventBus.subscribe<BackpressureEvent> { blockedSubscribed.complete(Unit) }
                .collect { awaitCancellation() }
        }
        val healthySubscriber = async(start = CoroutineStart.UNDISPATCHED) {
            EventBus.subscribe<BackpressureEvent> { healthySubscribed.complete(Unit) }
                .collect { healthyEvents += it.value }
        }

        try {
            blockedSubscribed.await()
            healthySubscribed.await()
            assertTrue(EventBus.tryPost(BackpressureEvent(0)))
            yield() // The blocked collector consumes one event, then stops receiving.

            repeat(64) { value ->
                assertTrue(EventBus.tryPost(BackpressureEvent(value + 1)))
                yield() // Keep the healthy channel drained while the other fills.
            }

            assertFalse(EventBus.tryPost(BackpressureEvent(65)))
            yield()
            assertEquals((0..64).toList(), healthyEvents)
        } finally {
            blockedSubscriber.cancelAndJoin()
            healthySubscriber.cancelAndJoin()
        }
    }

    private data class SingleConsumerEvent(val value: Int)
    private data class FirstEvent(val value: String)
    private data class SecondEvent(val value: String)
    private data class UndeliveredEvent(val value: String)
    private data object LifecycleEvent
    private data class BackpressureEvent(val value: Int)
    private open class ParentEvent(val value: String)
    private class ChildEvent(value: String) : ParentEvent(value)
}
