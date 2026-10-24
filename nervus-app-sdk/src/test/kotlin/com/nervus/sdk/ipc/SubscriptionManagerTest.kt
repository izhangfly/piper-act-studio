package com.nervus.sdk.ipc

import com.nervus.sdk.ipc.event.SubscriptionManager
import com.nervus.sdk.ipc.event.SubscriptionOverflowException
import io.github.nervusos.ipc.v1.DeliveryClass
import io.github.nervusos.ipc.v1.Event
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlin.test.Test
import kotlin.test.*

class SubscriptionManagerTest {

    @Test
    fun `subscribe and receive event`() = runBlocking {
        val mgr = SubscriptionManager()
        val flow = mgr.subscribe(1L)

        val collector = mutableListOf<Event>()
        val job = launch(Dispatchers.Unconfined) {
            flow.collect { collector.add(it) }
        }

        yield()

        val event = Event.newBuilder()
            .setSubscriptionId(1L)
            .setSequence(1)
            .build()
        mgr.pushEvent(event)

        yield()
        assertTrue(collector.size >= 1)
        if (collector.isNotEmpty()) {
            assertEquals(1L, collector[0].subscriptionId)
            assertEquals(1, collector[0].sequence)
        }

        job.cancel()
    }

    @Test
    fun `event to unknown subscription returns false`() {
        val mgr = SubscriptionManager()
        val event = Event.newBuilder()
            .setSubscriptionId(999L)
            .setSequence(1)
            .build()
        assertFalse(mgr.pushEvent(event))
    }

    @Test
    fun `subscribe after unsubscribe`(): Unit = runBlocking {
        val mgr = SubscriptionManager()
        mgr.subscribe(1L)

        val flow1 = mgr.unsubscribe(1L)
        assertNotNull(flow1)

        val flow2 = mgr.subscribe(1L)
        assertNotNull(flow2)
    }

    @Test
    fun `bind request id to subscription`() = runBlocking {
        val mgr = SubscriptionManager()
        mgr.bind(100L, 1L)

        val event = Event.newBuilder()
            .setSubscriptionId(1L)
            .setSequence(1)
            .build()
        assertTrue(mgr.pushEvent(event))
    }

    @Test
    fun `clear removes all subscriptions`() = runBlocking {
        val mgr = SubscriptionManager()
        mgr.subscribe(1L)
        mgr.subscribe(2L)
        assertEquals(2, mgr.subscriptionCount())

        mgr.clear()
        assertEquals(0, mgr.subscriptionCount())

        val event = Event.newBuilder()
            .setSubscriptionId(1L)
            .setSequence(1)
            .build()
        assertFalse(mgr.pushEvent(event))
    }

    @Test
    fun `close subscription stops events`() = runBlocking {
        val mgr = SubscriptionManager()
        mgr.subscribe(1L)

        mgr.closeSubscription(1L)

        val event = Event.newBuilder()
            .setSubscriptionId(1L)
            .setSequence(1)
            .build()
        assertFalse(mgr.pushEvent(event))
    }

    @Test
    fun `unsubscribe cleans up pending bindings`() = runBlocking {
        val mgr = SubscriptionManager()
        mgr.bind(42L, 1L)

        val event = Event.newBuilder()
            .setSubscriptionId(1L)
            .setSequence(1)
            .build()
        assertTrue(mgr.pushEvent(event))

        mgr.unsubscribe(1L)

        assertFalse(mgr.pushEvent(event))
    }

    @Test
    fun `close subscription cleans up pending bindings`() = runBlocking {
        val mgr = SubscriptionManager()
        mgr.bind(42L, 1L)

        mgr.closeSubscription(1L)

        val event = Event.newBuilder()
            .setSubscriptionId(1L)
            .setSequence(1)
            .build()
        assertFalse(mgr.pushEvent(event))
    }
}

// ---- delivery_class handling ------------------------------------------------
//
// These guard against the old "DROP_OLDEST for every class" implementation coming back. Its problem was
// that trySend never fails, so RELIABLE events were silently dropped when the consumer was slow, while the caller
// trusted the kernel's dropped == 0 and believed it had the full sequence.

private const val BUF = 4

private fun evt(subId: Long, seq: Long, dropped: Long = 0) = Event.newBuilder()
    .setSubscriptionId(subId)
    .setSequence(seq)
    .setDropped(dropped)
    .build()

class SubscriptionDeliveryClassTest {

    /** RELIABLE overflow [ends the subscription] instead of silently dropping. */
    @Test
    fun `reliable overflow terminates the subscription`() = runBlocking {
        val mgr = SubscriptionManager()
        val flow = mgr.subscribe(1L, DeliveryClass.DELIVERY_CLASS_RELIABLE, buffer = BUF)

        repeat(BUF) { assertTrue(mgr.pushEvent(evt(1L, (it + 1).toLong()))) }
        assertFalse(mgr.pushEvent(evt(1L, (BUF + 1).toLong())), "item ${BUF + 1} should overflow")

        // The consumer gets the buffered items, then an exception: it must [know] it lost something.
        val seen = mutableListOf<Event>()
        assertFailsWith<SubscriptionOverflowException> {
            flow.collect { seen.add(it) }
        }
        assertEquals(BUF, seen.size)

        // The subscription is void; later events aren't accepted.
        assertFalse(mgr.pushEvent(evt(1L, 99)))
        assertEquals(0, mgr.subscriptionCount())
    }

    /** Unknown classes are treated as RELIABLE: adding an enum value mustn't turn into "free to drop". */
    @Test
    fun `unknown delivery class is treated as reliable`() = runBlocking {
        val mgr = SubscriptionManager()
        mgr.subscribe(1L, DeliveryClass.DELIVERY_CLASS_UNSPECIFIED, buffer = BUF)

        repeat(BUF) { mgr.pushEvent(evt(1L, (it + 1).toLong())) }
        mgr.pushEvent(evt(1L, (BUF + 1).toLong()))

        assertEquals(0, mgr.subscriptionCount(), "overflow on an unknown class should end the subscription")
    }

    /**
     * STATE pushes out the [oldest] item and counts the pushed-out one in the new item's dropped.
     *
     * Getting the direction backwards (dropping new, keeping old) would invert STATE's meaning; dropping without counting
     * would let the caller see the sequence skip without knowing by how much, which is exactly why dropped exists.
     */
    @Test
    fun `state coalesces oldest and carries the drop count`() = runBlocking {
        val mgr = SubscriptionManager()
        val flow = mgr.subscribe(1L, DeliveryClass.DELIVERY_CLASS_STATE, buffer = BUF)

        repeat(BUF) { mgr.pushEvent(evt(1L, (it + 1).toLong())) }
        // Full: this one pushes out seq=1 and joins the queue
        assertTrue(mgr.pushEvent(evt(1L, 5)))

        mgr.closeSubscription(1L)
        val seen = mutableListOf<Event>()
        flow.collect { seen.add(it) }

        assertEquals(listOf(2L, 3L, 4L, 5L), seen.map { it.sequence }, "the oldest one should be dropped")
        assertEquals(1L, seen.last().dropped, "the pushed-out item must be counted in dropped")
    }

    /** dropped reported by the server and local drops are [added], not one overwriting the other. */
    @Test
    fun `server reported drops add to local drops`() = runBlocking {
        val mgr = SubscriptionManager()
        val flow = mgr.subscribe(1L, DeliveryClass.DELIVERY_CLASS_LOSSY, buffer = 1)

        assertTrue(mgr.pushEvent(evt(1L, 1)))
        assertFalse(mgr.pushEvent(evt(1L, 2)), "buffer full, one dropped locally")

        // Consume the queued item to free a slot
        assertEquals(1L, flow.takeOne().sequence)

        // The next item carries 3 server-side drops; plus the local 1 = 4
        assertTrue(mgr.pushEvent(evt(1L, 3, dropped = 3)))
        assertEquals(1L, mgr.localDropped(1L))

        mgr.closeSubscription(1L)
        val rest = mutableListOf<Event>()
        flow.collect { rest.add(it) }

        assertEquals(1, rest.size)
        assertEquals(4L, rest[0].dropped)
    }

    /** After LOSSY drops, the debt rides on the [next event actually delivered]. */
    @Test
    fun `lossy carries pending drops onto the next delivered event`() = runBlocking {
        val mgr = SubscriptionManager()
        val flow = mgr.subscribe(1L, DeliveryClass.DELIVERY_CLASS_LOSSY, buffer = 1)

        mgr.pushEvent(evt(1L, 1))
        assertFalse(mgr.pushEvent(evt(1L, 2)))
        assertFalse(mgr.pushEvent(evt(1L, 3)))

        val drained = mutableListOf<Event>()
        drained.add(flow.takeOne())

        assertTrue(mgr.pushEvent(evt(1L, 4)))
        assertEquals(2L, mgr.localDropped(1L))

        mgr.closeSubscription(1L)
        flow.collect { drained.add(it) }

        assertEquals(listOf(1L, 4L), drained.map { it.sequence })
        assertEquals(2L, drained[1].dropped, "the two owed must ride on seq=4")
    }
}

/** Takes one item from the Flow and leaves; used to free a buffer slot mid-test. */
private suspend fun Flow<Event>.takeOne(): Event {
    var out: Event? = null
    try {
        collect { out = it; throw StopCollect() }
    } catch (_: StopCollect) {
    }
    return out!!
}

private class StopCollect : RuntimeException(null, null, false, false)
