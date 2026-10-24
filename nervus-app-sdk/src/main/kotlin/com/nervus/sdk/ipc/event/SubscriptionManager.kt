package com.nervus.sdk.ipc.event

import io.github.nervusos.ipc.v1.DeliveryClass
import io.github.nervusos.ipc.v1.Event
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.logging.Logger

/** A RELIABLE subscription ended because the local buffer overflowed. */
class SubscriptionOverflowException(subscriptionId: Long) : Exception(
    "subscription $subscriptionId overflowed locally; " +
        "RELIABLE events cannot be dropped, so the subscription was terminated"
)

private const val DEFAULT_BUFFER = 64

/**
 * The client-side subscription buffer.
 *
 * # Why not DROP_OLDEST everywhere
 *
 * Every subscription here used to get a `BufferOverflow.DROP_OLDEST` Channel. That makes
 * `trySend` [always succeed], so:
 *
 * - RELIABLE events were **silently dropped** when the consumer was slow. The kernel guaranteed
 *   `Event.dropped == 0`, the caller trusted that it had the full sequence, and the gap was made
 *   inside its own process.
 * - The drop-counting code was dead (`trySend` never fails) and never logged a line.
 * - STATE dropped the **oldest** item. STATE means "only the latest value matters", so dropping old and keeping new
 *   was the right direction, but the count never went into any event's dropped, so coalescing became silent dropping.
 *
 * So each class is now handled separately, and [local drops ride on the next event actually delivered]:
 * only then does a caller who sees the sequence skip get the count.
 *
 * # Never block
 *
 * [pushEvent] is called by the read loop, which the whole connection shares. Blocking here would starve
 * request replies, Pings and other subscriptions on the same connection; one slow consumer dragging down the whole connection
 * is exactly what the delivery_class mechanism prevents.
 */
internal class SubscriptionManager {

    private class Entry(val subscriptionId: Long, buffer: Int) {
        val channel = Channel<Event>(buffer)
        var deliveryClass: DeliveryClass = DeliveryClass.DELIVERY_CLASS_UNSPECIFIED

        /** Local drops not yet attached to a delivered event. Reset on a successful delivery and added to that one. */
        var pendingDropped: Long = 0

        /** Total local drops, for debugging only (non-zero = this process consumes too slowly; no need to look at the kernel). */
        var localDropped: Long = 0
    }

    private val entries = HashMap<Long, Entry>()
    private val pendingBindings = HashMap<Long, Long>()
    private val logger = Logger.getLogger(SubscriptionManager::class.java.name)

    @Synchronized
    fun subscribe(
        subscriptionId: Long,
        deliveryClass: DeliveryClass = DeliveryClass.DELIVERY_CLASS_UNSPECIFIED,
        buffer: Int = DEFAULT_BUFFER
    ): Flow<Event> {
        val entry = entries.getOrPut(subscriptionId) { Entry(subscriptionId, buffer) }
        entry.deliveryClass = deliveryClass
        return entry.channel.receiveAsFlow()
    }

    @Synchronized
    fun bind(requestId: Long, serverSubscriptionId: Long) {
        pendingBindings[requestId] = serverSubscriptionId
        entries.getOrPut(serverSubscriptionId) { Entry(serverSubscriptionId, DEFAULT_BUFFER) }
    }

    /**
     * Delivers one event. Returns false if it didn't reach the consumer (no such subscription, or dropped locally).
     */
    @Synchronized
    fun pushEvent(event: Event): Boolean {
        val entry = entries[event.subscriptionId] ?: return false

        // carried is "how many were dropped locally since you last actually received one". It only counts as reported once
        // it rides on an event that's really delivered, so it's only reset after a success.
        var carried = entry.pendingDropped
        if (trySend(entry, event, carried)) {
            entry.pendingDropped = 0
            return true
        }

        return when (entry.deliveryClass) {
            DeliveryClass.DELIVERY_CLASS_STATE -> {
                // Coalesce: push the [oldest] item out of the queue and put the newest in. STATE means
                // "only the latest value matters"; dropping new and keeping old would invert that.
                if (entry.channel.tryReceive().isSuccess) {
                    carried++
                    if (trySend(entry, event, carried)) {
                        entry.pendingDropped = 0
                        return true
                    }
                }
                entry.localDropped++
                entry.pendingDropped = carried + 1
                false
            }

            DeliveryClass.DELIVERY_CLASS_LOSSY -> {
                entry.localDropped++
                entry.pendingDropped = carried + 1
                logger.fine("event dropped for subscription ${event.subscriptionId}")
                false
            }

            else -> {
                // RELIABLE, and any class this build doesn't know.
                //
                // [Unknown classes are treated as RELIABLE]: when the protocol adds a value, treating it as
                // "free to drop" is the most dangerous default, since the caller would think it had the full
                // sequence. Better to end the subscription and let it know.
                logger.severe(
                    "subscription ${event.subscriptionId} overflowed locally " +
                        "(class=${entry.deliveryClass}); terminating"
                )
                entry.channel.close(SubscriptionOverflowException(event.subscriptionId))
                entries.remove(event.subscriptionId)
                pendingBindings.values.removeAll { it == event.subscriptionId }
                false
            }
        }
    }

    /** Adds the local drop count to the event's own dropped and reports them together. */
    private fun trySend(entry: Entry, event: Event, carried: Long): Boolean {
        val outgoing = if (carried == 0L) {
            event
        } else {
            event.toBuilder().setDropped(event.dropped + carried).build()
        }
        return entry.channel.trySend(outgoing).isSuccess
    }

    @Synchronized
    fun setDeliveryClass(subscriptionId: Long, deliveryClass: DeliveryClass) {
        entries[subscriptionId]?.deliveryClass = deliveryClass
    }

    @Synchronized
    fun getDeliveryClass(subscriptionId: Long): DeliveryClass =
        entries[subscriptionId]?.deliveryClass ?: DeliveryClass.DELIVERY_CLASS_UNSPECIFIED

    /**
     * Total items this SDK's buffer has dropped (not counting drops on the nervud side).
     *
     * Kept apart from `Event.dropped` only for debugging: non-zero means [this process consumes too slowly], so enlarge
     * the buffer or make the collector faster, rather than looking at the kernel.
     */
    @Synchronized
    fun localDropped(subscriptionId: Long): Long = entries[subscriptionId]?.localDropped ?: 0

    @Synchronized
    fun unsubscribe(subscriptionId: Long): Flow<Event>? {
        pendingBindings.values.removeAll { it == subscriptionId }
        val entry = entries.remove(subscriptionId) ?: return null
        entry.channel.close()
        return entry.channel.receiveAsFlow()
    }

    @Synchronized
    fun closeSubscription(subscriptionId: Long) {
        pendingBindings.values.removeAll { it == subscriptionId }
        entries.remove(subscriptionId)?.channel?.close()
    }

    @Synchronized
    fun clear() {
        entries.values.forEach { it.channel.close() }
        entries.clear()
        pendingBindings.clear()
    }

    @Synchronized
    fun subscriptionCount(): Int = entries.size
}
