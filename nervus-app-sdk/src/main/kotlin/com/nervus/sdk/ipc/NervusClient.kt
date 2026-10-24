package com.nervus.sdk.ipc

import com.nervus.sdk.ipc.connection.*
import com.nervus.sdk.ipc.dispatch.DispatchHandler
import com.nervus.sdk.ipc.endpoint.EndpointCache
import com.nervus.sdk.ipc.endpoint.EndpointCacheKey
import com.nervus.sdk.ipc.endpoint.EndpointCacheValue
import com.nervus.sdk.ipc.event.SubscriptionManager
import com.nervus.sdk.ipc.rpc.PendingMap
import com.nervus.sdk.ipc.rpc.RequestIdGenerator
import io.github.nervusos.ipc.v1.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import java.io.EOFException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * Combines the four selection criteria into one [ResourceSelector]; returns null (no selector) when all four are empty.
 *
 * [labels and policy count towards "is there a selector" too]. Looking only at type/role, a call that
 * selects a device purely by labels would drop the selector entirely, and the kernel would report "you gave no
 * selector": wrong in the client, reported by the server, with a message pointing nowhere near the real cause.
 */
internal fun buildResourceSelector(
    resourceType: String,
    resourceRole: String,
    resourceLabels: Map<String, String>,
    selectionPolicy: ResourceSelectionPolicy
): ResourceSelector? {
    val empty = resourceType.isEmpty() &&
        resourceRole.isEmpty() &&
        resourceLabels.isEmpty() &&
        selectionPolicy == ResourceSelectionPolicy.RESOURCE_SELECTION_POLICY_UNSPECIFIED
    if (empty) return null
    return ResourceSelector.newBuilder()
        .setType(resourceType)
        .setRole(resourceRole)
        .putAllLabels(resourceLabels)
        .setPolicy(selectionPolicy)
        .build()
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    CLOSED
}

internal open class NervusClient(
    val sdkName: String = "nervus-app-sdk",
    val sdkVersion: String = "0.1.0"
) : AutoCloseable {
    @Volatile
    private var udSocket: UnixDomainSocket? = null
    @Volatile
    private var frameReader: FrameReader? = null
    @Volatile
    private var frameWriter: FrameWriter? = null
    private var handshakeResult: HandshakeResult? = null

    private val requestIdGenerator = RequestIdGenerator()
    private val pendingMap = PendingMap()
    private val resolvePending = PendingMap()

    // ---- Provider side (RegisterEndpoint / Dispatch) ----------------------------
    //
    // Why the consumer-side Client can register endpoints too: the kernel used to have [no] IPC for "start some app";
    // EnsureStarted could only be triggered by endpoint.Resolve starting an on-demand component.
    // So any app that "can be started" had to export an interface and register it, or nothing could wake it.
    //
    // In other words, a "pure consumer" doesn't exist on Nervus: an app with a UI that can be opened
    // is naturally also a provider. Registration lives here, instead of forcing the caller to open a second connection,
    // because endpoint_id is [connection-scoped]: two connections mean two sets of handles and two
    // component checks, which are hard to match up when something goes wrong.
    private val registerPending = PendingMap()
    private val launchPending = PendingMap()
    private val controlPending = PendingMap()
    private val dispatchHandler = DispatchHandler()
    private val registrations = ConcurrentHashMap<String, Long>()
    private val resolveEndpointIds = ConcurrentHashMap<Long, String>()
    private val endpointSubscriptions = ConcurrentHashMap<Long, MutableSet<Long>>()
    private val _endpointCache = EndpointCache()
    private val _subscriptionManager = SubscriptionManager()

    private var readerJob: Job? = null
    private var pingJob: Job? = null
    private var scope: CoroutineScope? = null

    @Volatile
    var connectionState: ConnectionState = ConnectionState.DISCONNECTED
        private set

    val limits: ConnectionLimits? get() = handshakeResult?.limits
    val endpointCache: EndpointCache get() = _endpointCache
    val subscriptionManager: SubscriptionManager get() = _subscriptionManager

    private val logger = Logger.getLogger(NervusClient::class.java.name)

    fun connect(
        socketPath: String,
        componentId: String = "",
        handshakeTimeoutMs: Long = 5000
    ): HandshakeResult {
        if (connectionState != ConnectionState.DISCONNECTED) {
            throw IllegalStateException("already connected or connecting")
        }
        connectionState = ConnectionState.CONNECTING
        val socket = UnixDomainSocket.connect(socketPath)
        try {
            val reader = FrameReader(socket.inputStream)
            val writer = FrameWriter(socket.outputStream)

            val handshake = HelloHandshake(reader, writer)
            val result = handshake.negotiate(
                minMajor = PROTOCOL_MAJOR_MIN,
                maxMajor = PROTOCOL_MAJOR_MAX,
                maxMinor = PROTOCOL_MINOR_MAX,
                sdkName = sdkName,
                sdkVersion = sdkVersion,
                componentId = componentId,
                timeoutMs = handshakeTimeoutMs
            )

            udSocket = socket
            frameReader = reader
            frameWriter = writer
            handshakeResult = result
            connectionState = ConnectionState.CONNECTED

            val idleTimeout = result.limits.idleTimeoutMs
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            readerJob = scope?.launch {
                runReaderLoop()
            }
            if (idleTimeout > 0) {
                pingJob = scope?.launch {
                    runPingLoop(idleTimeout)
                }
            }

            return result
        } catch (e: Exception) {
            connectionState = ConnectionState.CLOSED
            socket.close()
            throw e
        }
    }

    private suspend fun runPingLoop(idleTimeoutMs: Int) {
        val interval = idleTimeoutMs.toLong() / 2
        while (udSocket?.isOpen == true && connectionState == ConnectionState.CONNECTED) {
            delay(interval)
            val nonce = requestIdGenerator.next()
            frameWriter?.writeFrame(
                Envelope.newBuilder().setPing(
                    Ping.newBuilder().setNonce(nonce).build()
                ).build()
            )
        }
    }

    private fun closeConnection() {
        connectionState = ConnectionState.DISCONNECTED
        udSocket?.close()
        readerJob?.cancel()
        pingJob?.cancel()
        scope?.cancel()
        pendingMap.failAll()
        resolvePending.failAll()
        resolveEndpointIds.clear()
        _endpointCache.invalidateAll()
        _subscriptionManager.clear()
        // Provider-side state must be cleared too: when the connection drops, every endpoint_id registered on it becomes invalid
        // (endpoint_id is connection-scoped), and after reconnecting RegisterEndpoint must run again.
        // Otherwise we'd reply DispatchResult with old handles after reconnecting, and the kernel would discard them as late results
        registerPending.failAll()
        launchPending.failAll()
        controlPending.failAll()
        dispatchHandler.clear()
        registrations.clear()
    }

    private suspend fun runReaderLoop() {
        try {
            while (udSocket?.isOpen == true && connectionState == ConnectionState.CONNECTED) {
                val envelope = try {
                    frameReader?.readFrame()
                } catch (e: java.net.SocketTimeoutException) {
                    logger.fine("read timeout, checking connection")
                    continue
                } ?: break
                when (envelope.bodyCase) {
                    Envelope.BodyCase.RESPONSE -> {
                        handleResponse(envelope.response)
                    }
                    Envelope.BodyCase.EVENT -> {
                        handleEvent(envelope.event)
                    }
                    Envelope.BodyCase.RESOLVE_ENDPOINT_RESULT -> {
                        handleResolveEndpointResult(envelope.resolveEndpointResult)
                    }
                    Envelope.BodyCase.ENDPOINT_DIED -> {
                        handleEndpointDied(envelope.endpointDied)
                    }
                    Envelope.BodyCase.ENDPOINT_REVOKED -> {
                        handleEndpointRevoked(envelope.endpointRevoked)
                    }
                    Envelope.BodyCase.SUBSCRIBE_RESULT -> {
                        handleSubscribeResult(envelope.subscribeResult)
                    }
                    Envelope.BodyCase.UNSUBSCRIBE_RESULT -> {
                        handleUnsubscribeResult(envelope.unsubscribeResult)
                    }
                    Envelope.BodyCase.SUBSCRIPTION_CLOSED -> {
                        handleSubscriptionClosed(envelope.subscriptionClosed)
                    }
                    Envelope.BodyCase.REGISTER_ENDPOINT_RESULT -> {
                        handleRegisterEndpointResult(envelope.registerEndpointResult)
                    }
                    Envelope.BodyCase.LAUNCH_COMPONENT_RESULT -> {
                        handleLaunchComponentResult(envelope.launchComponentResult)
                    }
                    Envelope.BodyCase.ACQUIRE_CONTROL_RESULT -> {
                        val r = envelope.acquireControlResult
                        controlPending.complete(r.requestId, when (r.outcomeCase) {
                            AcquireControlResult.OutcomeCase.SUCCESS -> okResponse(r.success.toByteString())
                            AcquireControlResult.OutcomeCase.FAILURE -> Response.newBuilder().setFailure(r.failure).build()
                            else -> internalFailure("acquire_control_result with no outcome")
                        })
                    }
                    Envelope.BodyCase.RELEASE_CONTROL_RESULT -> {
                        val r = envelope.releaseControlResult
                        controlPending.complete(r.requestId, when (r.outcomeCase) {
                            ReleaseControlResult.OutcomeCase.SUCCESS -> okResponse(com.google.protobuf.ByteString.EMPTY)
                            ReleaseControlResult.OutcomeCase.FAILURE -> Response.newBuilder().setFailure(r.failure).build()
                            else -> internalFailure("release_control_result with no outcome")
                        })
                    }
                    Envelope.BodyCase.DISPATCH -> {
                        handleDispatch(envelope.dispatch)
                    }
                    Envelope.BodyCase.CANCEL_DISPATCH -> {
                        dispatchHandler.cancelDispatch(envelope.cancelDispatch.routeId)
                    }
                    Envelope.BodyCase.PING -> {
                        handlePing(envelope.ping)
                    }
                    Envelope.BodyCase.PONG -> {
                    }
                    else -> {
                        logger.severe("unknown envelope body: ${envelope.bodyCase} - closing connection")
                        closeConnection()
                        return
                    }
                }
            }
        } catch (e: EOFException) {
            logger.info("connection closed by server")
        } catch (e: ProtocolViolationException) {
            logger.severe("protocol violation: ${e.message}")
        } catch (e: Exception) {
            logger.fine("reader loop exited: ${e.message}")
        } finally {
            closeConnection()
        }
    }

    private fun handleResponse(response: Response) {
        if (response.hasSuccess()) {
            val code = response.success.code
            if (code != StatusCode.STATUS_CODE_OK && code != StatusCode.STATUS_CODE_ACCEPTED) {
                logger.severe("invalid success code: $code")
                return
            }
        } else if (response.hasFailure()) {
            val code = response.failure.code
            if (code == StatusCode.STATUS_CODE_UNSPECIFIED || code == StatusCode.STATUS_CODE_OK || code == StatusCode.STATUS_CODE_ACCEPTED) {
                logger.severe("invalid failure code: $code")
                return
            }
        } else {
            logger.warning("response with no outcome")
            return
        }
        pendingMap.complete(response.requestId, response)
    }

    private fun handleResolveEndpointResult(result: ResolveEndpointResult) {
        val interfaceId = resolveEndpointIds.remove(result.requestId) ?: return
        when (result.outcomeCase) {
            ResolveEndpointResult.OutcomeCase.SUCCESS -> {
                val success = result.success
                val value = EndpointCacheValue(
                    endpointId = success.endpointId,
                    interfaceMajor = success.interfaceMajor,
                    interfaceMinor = success.interfaceMinor,
                    schemaHash = success.interfaceSchemaHash.toByteArray(),
                    resourceHandle = success.resourceHandle
                )
                _endpointCache.put(
                    EndpointCacheKey(
                        interfaceId = interfaceId,
                        interfaceMajor = success.interfaceMajor,
                        interfaceMinor = success.interfaceMinor,
                        schemaHash = success.interfaceSchemaHash.toByteArray()
                    ),
                    value
                )
                resolvePending.complete(result.requestId, Response.newBuilder()
                    .setSuccess(Success.newBuilder()
                        .setCode(StatusCode.STATUS_CODE_OK)
                        .setPayload(success.toByteString())
                        .build())
                    .build())
            }
            ResolveEndpointResult.OutcomeCase.FAILURE -> {
                resolvePending.complete(result.requestId, Response.newBuilder()
                    .setFailure(result.failure)
                    .build())
            }
            else -> {
                logger.warning("resolve_endpoint_result with no outcome")
            }
        }
    }

    private fun handleEvent(event: Event) {
        _subscriptionManager.pushEvent(event)
    }

    /**
     * Callback for when an endpoint becomes invalid (died or withdrawn), installed by the layer above.
     *
     * # Why it's required
     *
     * This class clears its own `_endpointCache`, but **the layer above may have its own cache**:
     * `NervusApp.resolvedEndpoints` remembers `interfaceId -> endpoint` to avoid resolving on every
     * call. Clearing one without the other leaves the upper layer calling a dead endpoint forever, with
     * a permanent NOT_FOUND as the symptom, when a fresh Resolve would happily start the on-demand
     * Provider again.
     *
     * This is especially deadly for on-demand components: they take this path when they **exit normally**
     * (reaped by the kernel after an idle timeout), with no fault at all.
     */
    @Volatile
    var onEndpointInvalidated: (() -> Unit)? = null

    private fun handleEndpointDied(died: EndpointDied) {
        _endpointCache.invalidateAll()
        onEndpointInvalidated?.invoke()
        closeEndpointSubscriptions(died.endpointId)
        when (died.reason) {
            EndpointDiedReason.ENDPOINT_DIED_REASON_SERVICE_SHUTTING_DOWN,
            EndpointDiedReason.ENDPOINT_DIED_REASON_SERVICE_RESTARTED -> {
                logger.info("endpoint ${died.endpointId} died (${died.reason}), may be resolved again later")
            }
            EndpointDiedReason.ENDPOINT_DIED_REASON_SERVICE_GONE,
            EndpointDiedReason.ENDPOINT_DIED_REASON_RESOURCE_FAULT -> {
                logger.warning("endpoint ${died.endpointId} died (${died.reason}), retry with backoff")
            }
            else -> {
                logger.warning("endpoint ${died.endpointId} died (unknown reason)")
            }
        }
    }

    private fun handleEndpointRevoked(revoked: EndpointRevoked) {
        _endpointCache.invalidateAll()
        onEndpointInvalidated?.invoke()
        closeEndpointSubscriptions(revoked.endpointId)
        logger.severe("endpoint ${revoked.endpointId} revoked (${revoked.reason}) - do not retry")
    }

    private fun closeEndpointSubscriptions(endpointId: Long) {
        val subs = endpointSubscriptions.remove(endpointId) ?: return
        for (subId in subs) {
            _subscriptionManager.closeSubscription(subId)
        }
    }

    private fun handleSubscribeResult(result: SubscribeResult) {
        val response = Response.newBuilder().apply {
            when (result.outcomeCase) {
                SubscribeResult.OutcomeCase.SUCCESS -> {
                    val success = result.success
                    _subscriptionManager.bind(result.requestId, success.subscriptionId)
                    _subscriptionManager.setDeliveryClass(success.subscriptionId, success.deliveryClass)
                    setSuccess(Success.newBuilder()
                        .setCode(StatusCode.STATUS_CODE_OK)
                        .setPayload(success.toByteString())
                        .build())
                }
                SubscribeResult.OutcomeCase.FAILURE -> {
                    setFailure(result.failure)
                }
                else -> {
                    setFailure(Failure.newBuilder()
                        .setCode(StatusCode.STATUS_CODE_INTERNAL)
                        .setPublicMessage("subscribe_result with no outcome")
                        .build())
                }
            }
        }.build()
        resolvePending.complete(result.requestId, response)
    }

    private fun handleUnsubscribeResult(result: UnsubscribeResult) {
        val response = Response.newBuilder().apply {
            when (result.outcomeCase) {
                UnsubscribeResult.OutcomeCase.SUCCESS -> {
                    setSuccess(Success.newBuilder()
                        .setCode(StatusCode.STATUS_CODE_OK)
                        .build())
                }
                UnsubscribeResult.OutcomeCase.FAILURE -> {
                    setFailure(result.failure)
                }
                else -> {
                    logger.warning("unsubscribe_result with no outcome")
                    setFailure(Failure.newBuilder()
                        .setCode(StatusCode.STATUS_CODE_INTERNAL)
                        .setPublicMessage("unsubscribe_result with no outcome")
                        .build())
                }
            }
        }.build()
        resolvePending.complete(result.requestId, response)
    }

    private fun handleSubscriptionClosed(closed: SubscriptionClosed) {
        _subscriptionManager.closeSubscription(closed.subscriptionId)
        endpointSubscriptions.values.forEach { it.remove(closed.subscriptionId) }
    }

    private fun handlePing(ping: Ping) {
        val pong = Pong.newBuilder().setNonce(ping.nonce).build()
        frameWriter?.writeFrame(
            Envelope.newBuilder().setPong(pong).build()
        )
    }

    /** The provider-side dispatch router. ServiceStub hangs method handlers on it */
    val handler: DispatchHandler get() = dispatchHandler

    /**
     * Registers an Interface this component implements with nervud.
     *
     * Only interfaces declared in the manifest's `components[].exports` can be registered, with the matching permission
     * (`visibility: public` needs perm.service.register, `package` needs
     * perm.service.register.private): the seven admission steps in the kernel's endpoint/register.go.
     *
     * Returns the server-side endpoint_id. It and the endpoint_id a caller gets from Resolve are
     * **two namespaces**: the same number on two connections means nothing.
     */
    fun registerEndpoint(
        interfaceId: String,
        interfaceMajor: Int = 1,
        interfaceMinor: Int = 0,
        schemaHash: ByteArray = ByteArray(0),
        resourceHandle: String = "",
        timeoutMs: Int = 5000
    ): CompletableFuture<Long> {
        val writer = frameWriter ?: throw IllegalStateException("not connected")
        val requestId = requestIdGenerator.next()
        val future = registerPending.register(requestId)

        try {
            writer.writeFrame(
                Envelope.newBuilder().setRegisterEndpoint(
                    RegisterEndpoint.newBuilder()
                        .setRequestId(requestId)
                        .setInterfaceId(interfaceId)
                        .setInterfaceMajor(interfaceMajor)
                        .setInterfaceMinor(interfaceMinor)
                        .setInterfaceSchemaHash(com.google.protobuf.ByteString.copyFrom(schemaHash))
                        .setResourceHandle(resourceHandle)
                        .build()
                ).build()
            )
            if (timeoutMs > 0) {
                future.orTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            }
        } catch (e: Exception) {
            registerPending.failOne(requestId, StatusCode.STATUS_CODE_UNAVAILABLE, "write failed: ${e.message}")
        }

        return future.thenApply { response ->
            if (response.hasFailure()) {
                throw RuntimeException("register endpoint '$interfaceId' rejected: ${response.failure.publicMessage}")
            }
            val endpointId = RegisterEndpointSuccess.parseFrom(response.success.payload).endpointId
            registrations[interfaceId] = endpointId
            endpointId
        }
    }

    private fun handleRegisterEndpointResult(result: RegisterEndpointResult) {
        val response = when (result.outcomeCase) {
            RegisterEndpointResult.OutcomeCase.SUCCESS ->
                Response.newBuilder().setSuccess(
                    Success.newBuilder()
                        .setCode(StatusCode.STATUS_CODE_OK)
                        .setPayload(result.success.toByteString())
                        .build()
                ).build()
            RegisterEndpointResult.OutcomeCase.FAILURE ->
                Response.newBuilder().setFailure(result.failure).build()
            else ->
                Response.newBuilder().setFailure(
                    Failure.newBuilder()
                        .setCode(StatusCode.STATUS_CODE_INTERNAL)
                        .setPublicMessage("register_endpoint_result with no outcome")
                        .build()
                ).build()
        }
        registerPending.complete(result.requestId, response)
    }

    /**
     * Asks nervud to start an installed component (the Envelope's LaunchComponent, body 80).
     *
     * Needs `perm.system.launch` (MinTrust=Platform): only platform-signed packages shipped with the system image
     * can have it. The launcher and session services use it.
     *
     * Success [only] means that component is now running; it sets up no call relationship. To talk to it you still go
     * through [resolveEndpoint], with every permission and visibility check that involves.
     *
     * @return true if the component was already running before this request
     */
    fun launchComponent(
        packageId: String,
        componentId: String,
        timeoutMs: Int = 30_000,
    ): CompletableFuture<Boolean> {
        val writer = frameWriter ?: throw IllegalStateException("not connected")
        val requestId = requestIdGenerator.next()
        val future = launchPending.register(requestId)

        try {
            writer.writeFrame(
                Envelope.newBuilder().setLaunchComponent(
                    LaunchComponent.newBuilder()
                        .setRequestId(requestId)
                        .setPackageId(packageId)
                        .setComponentId(componentId)
                        .build()
                ).build()
            )
            if (timeoutMs > 0) {
                future.orTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            }
        } catch (e: Exception) {
            launchPending.failOne(requestId, StatusCode.STATUS_CODE_UNAVAILABLE, "write failed: ${e.message}")
        }

        return future.thenApply { response ->
            if (response.hasFailure()) {
                throw RuntimeException(
                    "launch $packageId/$componentId rejected: ${response.failure.publicMessage} " +
                        "(code=${response.failure.code})"
                )
            }
            LaunchComponentSuccess.parseFrom(response.success.payload).alreadyRunning
        }
    }

    /**
     * Requests a ControlLease on one resource (the Envelope's AcquireControl, body 70).
     *
     * The lease belongs to this connection and lapses when it closes. Asking again from the same
     * connection with the same class is an idempotent renewal: same lease_id, deadline extended,
     * motion epoch unchanged. A HUMAN lease also carries a short deadman (300 ms by default) that every
     * lease-gated command refreshes; renew it while the operator is active, let it lapse when they stop.
     *
     * Fails with [ControlLeaseException], whose [ControlLeaseException.reason] says who holds the
     * resource (HELD_BY_HUMAN / HELD_BY_AI), whether Safety has latched, and so on.
     */
    fun acquireControl(
        selector: ResourceSelector,
        controllerClass: ControllerClass,
        requestedDeadlineNanos: Long = 0,
        timeoutMs: Int = 5_000,
    ): CompletableFuture<AcquireControlSuccess> {
        val writer = frameWriter ?: throw IllegalStateException("not connected")
        val requestId = requestIdGenerator.next()
        val future = controlPending.register(requestId)
        try {
            writer.writeFrame(
                Envelope.newBuilder().setAcquireControl(
                    AcquireControl.newBuilder()
                        .setRequestId(requestId)
                        .setControllerClass(controllerClass)
                        .setResource(selector)
                        .setRequestedDeadlineNanos(requestedDeadlineNanos)
                        .build()
                ).build()
            )
            if (timeoutMs > 0) future.orTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            controlPending.failOne(requestId, StatusCode.STATUS_CODE_UNAVAILABLE, "write failed: ${e.message}")
        }
        return future.thenApply { response ->
            if (response.hasFailure()) throw ControlLeaseException.from(response.failure)
            AcquireControlSuccess.parseFrom(response.success.payload)
        }
    }

    /** Gives a lease back (body 72). Releasing moves the resource to a new motion epoch. */
    fun releaseControl(leaseId: Long, timeoutMs: Int = 5_000): CompletableFuture<Unit> {
        val writer = frameWriter ?: throw IllegalStateException("not connected")
        val requestId = requestIdGenerator.next()
        val future = controlPending.register(requestId)
        try {
            writer.writeFrame(
                Envelope.newBuilder().setReleaseControl(
                    ReleaseControl.newBuilder().setRequestId(requestId).setLeaseId(leaseId).build()
                ).build()
            )
            if (timeoutMs > 0) future.orTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            controlPending.failOne(requestId, StatusCode.STATUS_CODE_UNAVAILABLE, "write failed: ${e.message}")
        }
        return future.thenApply { response ->
            if (response.hasFailure()) throw ControlLeaseException.from(response.failure)
        }
    }

    private fun okResponse(payload: com.google.protobuf.ByteString): Response =
        Response.newBuilder().setSuccess(
            Success.newBuilder().setCode(StatusCode.STATUS_CODE_OK).setPayload(payload).build()
        ).build()

    private fun internalFailure(message: String): Response =
        Response.newBuilder().setFailure(
            Failure.newBuilder().setCode(StatusCode.STATUS_CODE_INTERNAL).setPublicMessage(message).build()
        ).build()

    private fun handleLaunchComponentResult(result: LaunchComponentResult) {
        val response = when (result.outcomeCase) {
            LaunchComponentResult.OutcomeCase.SUCCESS ->
                Response.newBuilder().setSuccess(
                    Success.newBuilder()
                        .setCode(StatusCode.STATUS_CODE_OK)
                        .setPayload(result.success.toByteString())
                        .build()
                ).build()
            LaunchComponentResult.OutcomeCase.FAILURE ->
                Response.newBuilder().setFailure(result.failure).build()
            else ->
                Response.newBuilder().setFailure(
                    Failure.newBuilder()
                        .setCode(StatusCode.STATUS_CODE_INTERNAL)
                        .setPublicMessage("launch_component_result with no outcome")
                        .build()
                ).build()
        }
        launchPending.complete(result.requestId, response)
    }

    private fun handleDispatch(dispatch: Dispatch) {
        val dispatchResult = dispatchHandler.dispatch(dispatch)
        try {
            frameWriter?.writeFrame(
                Envelope.newBuilder().setDispatchResult(dispatchResult).build()
            )
        } catch (e: Exception) {
            logger.severe("failed to write dispatch result: ${e.message}")
        }
    }

    /**
     * Resolves an interface, giving an endpoint_id calls can be sent to.
     *
     * [Interfaces bound to a resource must give their own selector]. In v1 an empty one made nervud implicitly take
     * `{nervus.resource.motion.base, main}`; v2 removed that default, which made "I forgot to fill it in"
     * and "I want the base" the same thing. Now an empty one is refused by the kernel instead of silently getting the base.
     *
     * To select a device, prefer [resourceLabels] (such as `nervus.camera.facing=front`) over
     * [resourceRole]: a role is a stable number on one machine and may not keep that name on another board.
     *
     * [selectionPolicy] unset = REQUIRE_UNIQUE: several matches is an error rather than picking
     * one for you. Use SYSTEM_PREFERRED to let the system choose.
     */
    fun resolveEndpoint(
        interfaceId: String,
        minInterfaceMajor: Int = 1,
        maxInterfaceMajor: Int = 1,
        resourceType: String = "",
        resourceRole: String = "",
        resourceLabels: Map<String, String> = emptyMap(),
        selectionPolicy: ResourceSelectionPolicy =
            ResourceSelectionPolicy.RESOURCE_SELECTION_POLICY_UNSPECIFIED,
        explicitComponent: String = "",
        timeoutMs: Int = handshakeResult?.limits?.defaultTimeoutMs ?: 5000
    ): CompletableFuture<Response> {
        val writer = frameWriter ?: throw IllegalStateException("not connected")
        val requestId = requestIdGenerator.next()
        val future = resolvePending.register(requestId)
        resolveEndpointIds[requestId] = interfaceId

        try {
            val builder = ResolveEndpoint.newBuilder()
                .setRequestId(requestId)
                .setInterfaceId(interfaceId)
                .setMinInterfaceMajor(minInterfaceMajor)
                .setMaxInterfaceMajor(maxInterfaceMajor)

            buildResourceSelector(resourceType, resourceRole, resourceLabels, selectionPolicy)
                ?.let { builder.selector = it }
            if (explicitComponent.isNotEmpty()) {
                builder.explicitComponent = explicitComponent
            }

            writer.writeFrame(
                Envelope.newBuilder().setResolveEndpoint(builder.build()).build()
            )

            if (timeoutMs > 0) {
                future.orTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            }
        } catch (e: Exception) {
            resolvePending.failOne(requestId, StatusCode.STATUS_CODE_UNAVAILABLE, "write failed: ${e.message}")
        }
        return future
    }

    fun call(
        endpointId: Long,
        methodId: Int,
        payload: ByteArray = ByteArray(0),
        timeoutMs: Int = handshakeResult?.limits?.defaultTimeoutMs ?: 5000
    ): CompletableFuture<Response> {
        val limits = handshakeResult?.limits
        if (limits != null) {
            if (payload.size > limits.defaultMethodPayloadBytes && limits.defaultMethodPayloadBytes > 0) {
                val f = CompletableFuture<Response>()
                f.completeExceptionally(IllegalStateException("payload too large: ${payload.size} > ${limits.defaultMethodPayloadBytes}"))
                return f
            }
            if (pendingMap.size() >= limits.maxInflightRequests) {
                val f = CompletableFuture<Response>()
                f.completeExceptionally(IllegalStateException("max inflight requests (${limits.maxInflightRequests}) reached"))
                return f
            }
        }
        val effectiveTimeout = if (limits != null && limits.maxTimeoutMs > 0) timeoutMs.coerceAtMost(limits.maxTimeoutMs) else timeoutMs
        val writer = frameWriter ?: throw IllegalStateException("not connected")
        val requestId = requestIdGenerator.next()
        val future = pendingMap.register(requestId, payload.size)

        try {
            val request = Request.newBuilder()
                .setRequestId(requestId)
                .setEndpointId(endpointId)
                .setMethodId(methodId)
                .setTimeoutMs(effectiveTimeout)
                .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
                .build()

            writer.writeFrame(
                Envelope.newBuilder().setRequest(request).build()
            )

            if (effectiveTimeout > 0) {
                future.orTimeout(effectiveTimeout.toLong(), TimeUnit.MILLISECONDS)
            }
        } catch (e: Exception) {
            pendingMap.failOne(requestId, StatusCode.STATUS_CODE_UNAVAILABLE, "write failed: ${e.message}")
        }
        return future
    }

    fun cancel(requestId: Long) {
        val writer = frameWriter ?: throw IllegalStateException("not connected")
        pendingMap.cancelOne(requestId)
        val cancel = Cancel.newBuilder().setRequestId(requestId).build()
        try {
            writer.writeFrame(
                Envelope.newBuilder().setCancel(cancel).build()
            )
        } catch (e: Exception) {
            logger.fine("failed to write cancel frame: ${e.message}")
        }
    }

    /**
     * Subscribes to an event.
     *
     * [scope] is the [instance scope]: one endpoint can run several independently observable
     * instances at once (several streams on one camera, every operation on the machine on one built-in endpoint).
     * When an event declares `EventMeta.scoped` this parameter is required and non-zero; otherwise it must be 0. nervud
     * refuses both mistakes rather than letting them slide.
     *
     * The Provider decides what value to use, usually the handle it returned to you (stream_id,
     * operation_id). Subscribing to an instance that isn't yours returns NOT_FOUND.
     */
    fun subscribe(
        endpointId: Long,
        eventId: Int,
        payload: ByteArray = ByteArray(0),
        scope: Long = 0,
        timeoutMs: Int = handshakeResult?.limits?.defaultTimeoutMs ?: 5000
    ): CompletableFuture<Flow<Event>> {
        val limits = handshakeResult?.limits
        if (limits != null && _subscriptionManager.subscriptionCount() >= limits.maxSubscriptions) {
            val future = CompletableFuture<Flow<Event>>()
            future.completeExceptionally(IllegalStateException("max subscriptions (${limits.maxSubscriptions}) reached"))
            return future
        }
        val writer = frameWriter ?: throw IllegalStateException("not connected")
        val requestId = requestIdGenerator.next()
        val future = resolvePending.register(requestId)

        try {
            val subscribe = Subscribe.newBuilder()
                .setRequestId(requestId)
                .setEndpointId(endpointId)
                .setEventId(eventId)
                .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
                .setScope(scope)
                .build()

            writer.writeFrame(
                Envelope.newBuilder().setSubscribe(subscribe).build()
            )
        } catch (e: Exception) {
            resolvePending.failOne(requestId, StatusCode.STATUS_CODE_UNAVAILABLE, "write failed: ${e.message}")
        }

        val resultFuture: CompletableFuture<Flow<Event>> = future.thenApply { response ->
            if (response.hasSuccess()) {
                val subResult = SubscribeSuccess.parseFrom(response.success.payload)
                val subId = subResult.subscriptionId
                val flow = _subscriptionManager.subscribe(subId, subResult.deliveryClass)
                endpointSubscriptions.computeIfAbsent(endpointId) { ConcurrentHashMap.newKeySet() }.add(subId)
                flow
            } else {
                throw RuntimeException("subscribe failed: ${response.failure.publicMessage}")
            }
        }

        if (timeoutMs > 0) {
            future.orTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        }
        return resultFuture
    }

    fun unsubscribe(subscriptionId: Long, timeoutMs: Int = 5000): CompletableFuture<Response> {
        val writer = frameWriter ?: throw IllegalStateException("not connected")
        val requestId = requestIdGenerator.next()
        val future = resolvePending.register(requestId)

        try {
            writer.writeFrame(
                Envelope.newBuilder()
                    .setUnsubscribe(Unsubscribe.newBuilder()
                        .setRequestId(requestId)
                        .setSubscriptionId(subscriptionId)
                        .build())
                    .build()
            )
        } catch (e: Exception) {
            resolvePending.failOne(requestId, StatusCode.STATUS_CODE_UNAVAILABLE, "write failed: ${e.message}")
        }

        if (timeoutMs > 0) {
            future.orTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        }
        return future
    }

    override fun close() {
        connectionState = ConnectionState.CLOSED
        udSocket?.close()
        readerJob?.cancel()
        pingJob?.cancel()
        scope?.cancel()
        pendingMap.failAll()
        resolvePending.failAll()
        resolveEndpointIds.clear()
        endpointSubscriptions.clear()
        _endpointCache.invalidateAll()
        _subscriptionManager.clear()
    }
}
