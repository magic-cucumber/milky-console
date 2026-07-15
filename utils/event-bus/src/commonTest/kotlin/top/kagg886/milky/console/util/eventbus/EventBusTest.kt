package top.kagg886.milky.console.util.eventbus

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventBusTest {
    @Test
    fun anEventIsDeliveredToOnlyOneSameTypeSubscriber() = runBlocking {
        val event = SingleConsumerEvent(42)
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            EventBus.subscribe<SingleConsumerEvent>().first()
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            EventBus.subscribe<SingleConsumerEvent>().first()
        }

        try {
            EventBus.post(event)

            val delivered = withTimeout(1_000) {
                if (first.isCompleted) first.await() else second.await()
            }
            assertEquals(event, delivered)

            val other = if (first.isCompleted) second else first
            assertNull(withTimeoutOrNull(100) { other.await() })
        } finally {
            first.cancelAndJoin()
            second.cancelAndJoin()
        }
    }

    @Test
    fun eventTypesUseIndependentQueues() = runBlocking {
        EventBus.post(FirstEvent("first"))
        EventBus.post(SecondEvent("second"))

        assertEquals(FirstEvent("first"), EventBus.subscribe<FirstEvent>().first())
        assertEquals(SecondEvent("second"), EventBus.subscribe<SecondEvent>().first())
    }

    private data class SingleConsumerEvent(val value: Int)
    private data class FirstEvent(val value: String)
    private data class SecondEvent(val value: String)
}
