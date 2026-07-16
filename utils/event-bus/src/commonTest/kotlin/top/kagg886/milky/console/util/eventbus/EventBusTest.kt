package top.kagg886.milky.console.util.eventbus

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    private data class SingleConsumerEvent(val value: Int)
    private data class FirstEvent(val value: String)
    private data class SecondEvent(val value: String)
    private data class UndeliveredEvent(val value: String)
    private data object LifecycleEvent
}
