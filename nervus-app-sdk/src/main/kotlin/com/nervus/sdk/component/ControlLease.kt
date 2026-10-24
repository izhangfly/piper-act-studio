package com.nervus.sdk.component

import com.nervus.sdk.ipc.NervusClient
import io.github.nervusos.ipc.v1.AcquireControlSuccess
import io.github.nervusos.ipc.v1.ControllerClass
import io.github.nervusos.ipc.v1.ResourceSelector
import java.util.concurrent.TimeUnit

/**
 * A ControlLease this component holds on one resource, from [NervusApp.acquireControl].
 *
 * Methods that declare `requires_control_lease` (moving an arm or a base, opening a gripper) only
 * pass nervud's method gate while the calling connection holds a valid lease on the resource the
 * endpoint is bound to. The lease is tied to the connection: it can't be handed to another process,
 * and it lapses when the connection closes.
 *
 * Keeping it: a lease has a deadline (TTL), and a HUMAN lease also a deadman (300 ms by default).
 * Every lease-gated command refreshes the deadman; [renew] extends the deadline and refreshes the
 * deadman too. Renew while the operator is actively steering and stop renewing when they let go: the
 * lease then lapses on its own, which is exactly what the deadman is for.
 *
 * Losing it: Safety, pre-emption by a HUMAN lease, or a lapse all move the resource to a new motion
 * epoch. Commands from the old epoch are refused by the kernel and by the Provider, so after a
 * refusal call [renew], which acquires a fresh lease if the old one is gone.
 */
class ControlLease internal constructor(
    private val client: NervusClient,
    val selector: ResourceSelector,
    val controllerClass: ControllerClass,
    first: AcquireControlSuccess,
) {
    @Volatile
    var leaseId: Long = first.leaseId
        private set

    @Volatile
    var motionEpoch: Long = first.motionEpoch
        private set

    /** The resource handle nervud resolved the selector to, such as "arm.main". */
    @Volatile
    var resourceHandle: String = first.resourceHandle
        private set

    @Volatile
    var released: Boolean = false
        private set

    /**
     * Extends the lease (same lease_id and epoch while it is still valid) or, if it lapsed, acquires
     * a new one. Throws [com.nervus.sdk.ipc.ControlLeaseException] when nervud refuses.
     *
     * @return true if this is still the same lease, false if a new one (new epoch) replaced it
     */
    fun renew(timeoutMs: Long = 2_000): Boolean {
        val s = client.acquireControl(selector, controllerClass).get(timeoutMs, TimeUnit.MILLISECONDS)
        val same = s.leaseId == leaseId && s.motionEpoch == motionEpoch
        leaseId = s.leaseId
        motionEpoch = s.motionEpoch
        resourceHandle = s.resourceHandle
        released = false
        return same
    }

    /** Gives control back. Safe to call more than once. */
    fun release(timeoutMs: Long = 2_000) {
        if (released) return
        released = true
        runCatching { client.releaseControl(leaseId).get(timeoutMs, TimeUnit.MILLISECONDS) }
    }
}
