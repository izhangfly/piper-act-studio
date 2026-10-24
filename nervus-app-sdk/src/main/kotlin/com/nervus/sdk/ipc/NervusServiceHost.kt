package com.nervus.sdk.ipc

import com.nervus.sdk.ipc.connection.*
import com.nervus.sdk.ipc.dispatch.DispatchHandler
import io.github.nervusos.ipc.v1.*
import kotlinx.coroutines.*
import java.io.EOFException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import com.nervus.sdk.ipc.rpc.PendingMap
import com.nervus.sdk.ipc.rpc.RequestIdGenerator

internal data class Registration(
    val endpointId: Long,
    val interfaceId: String,
    val interfaceMajor: Int,
    val interfaceMinor: Int
)

internal class NervusServiceHost(
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
    private val registerPending = PendingMap()

    /**
     * Pending table for Requests this Service [initiates].
     *
     * Kept apart from [registerPending]: that table only holds replies to endpoint register/unregister, and its
     * request_id space shares one generator with ordinary method calls, but the two kinds of reply come in different
     * Envelope bodies (RegisterEndpointResult / Response). In one table,
     * a failed registration could complete a method call's future, and the other way round.
     */
    private val callPending = PendingMap()

    /** Pending table for Resolves this Service initiates; kept apart from [callPending] for the same reason. */
    private val resolvePending = PendingMap()

    private val dispatchHandler = DispatchHandler()
    private val registrations = HashMap<String, Registration>()

    private var readerJob: Job? = null
    private var pingJob: Job? = null
    private var scope: CoroutineScope? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    private val logger = Logger.getLogger(NervusServiceHost::class.java.name)

    fun connect(
        socketPath: String,
        componentId: String = "",
        handshakeTimeoutMs: Long = 5000
    ) {
        if (isConnected) {
            throw IllegalStateException("already connected")
        }
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
            isConnected = true

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
        } catch (e: Exception) {
            isConnected = false
            socket.close()
            throw e
        }
    }

    val handler: DispatchHandler get() = dispatchHandler

    fun registerEndpoint(
        interfaceId: String,
        interfaceMajor: Int,
        interfaceMinor: Int,
        schemaHash: ByteArray = ByteArray(0),
        resourceHandle: String = "",
        timeoutMs: Int = 5000
    ): CompletableFuture<Registration> {
        val writer = frameWriter ?: throw IllegalStateException("not connected")
        val requestId = requestIdGenerator.next()
        val future = registerPending.register(requestId)

        try {
            val register = RegisterEndpoint.newBuilder()
                .setRequestId(requestId)
                .setInterfaceId(interfaceId)
                .setInterfaceMajor(interfaceMajor)
                .setInterfaceMinor(interfaceMinor)
                .setInterfaceSchemaHash(com.google.protobuf.ByteString.copyFrom(schemaHash))
                .setResourceHandle(resourceHandle)
                .build()

            writer.writeFrame(
                Envelope.newBuilder().setRegisterEndpoint(register).build()
            )
        } catch (e: Exception) {
            registerPending.failOne(requestId, StatusCode.STATUS_CODE_UNAVAILABLE, "write failed: ${e.message}")
        }

        if (timeoutMs > 0) {
            future.orTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        }

        return future.thenApply { response ->
            if (response.hasFailure()) {
                throw RuntimeException("register endpoint rejected: ${response.failure.publicMessage}")
            }
            val success = RegisterEndpointSuccess.parseFrom(response.success.payload)
            val registration = Registration(
                endpointId = success.endpointId,
                interfaceId = interfaceId,
                interfaceMajor = interfaceMajor,
                interfaceMinor = interfaceMinor
            )
            registrations[interfaceId] = registration
            registration
        }
    }

    // ---- Provider-side events -----------------------------------------------------
    //
    // [Only Services may call these three methods]. When nervud gets PublishEvent or BindEventScope
    // from an ordinary App it [closes the connection]: it audits the wrong direction as a protocol violation instead of
    // replying with an error code. That's why NervusClient (the App path) has no matching methods; they weren't forgotten.

    /**
     * Pushes one event to every subscriber.
     *
     * One-way: no request ID, no receipt. A refused event (the endpoint isn't yours, the contract never declared
     * this event_id, the payload is too big) shows up as subscribers [receiving nothing]; nothing is thrown locally.
     * To debug it, check nervud's audit log.
     *
     * [monotonicTimestampNanos] is [when the event happened], which you take yourself. The SDK doesn't fill in
     * the current time for you: that would be "when it was pushed", and only you know how far that is from the sampling time, while
     * a plausible wrong time is harder to spot than an obvious 0. Leave it 0 if you don't have one.
     */
    fun publishEvent(
        endpointId: Long,
        eventId: Int,
        payload: ByteArray = ByteArray(0),
        monotonicTimestampNanos: Long = 0
    ) {
        val writer = frameWriter ?: throw IllegalStateException("not connected")
        writer.writeFrame(
            Envelope.newBuilder()
                .setPublishEvent(
                    PublishEvent.newBuilder()
                        .setEndpointId(endpointId)
                        .setEventId(eventId)
                        .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
                        .setMonotonicTimestampNanos(monotonicTimestampNanos)
                        .build()
                )
                .build()
        )
    }

    /**
     * Records an instance's ownership: `scope` belongs to the caller of the call that [originRouteId] identifies.
     *
     * For events declaring `EventMeta.scoped`, subscribers must give a scope to subscribe, and nervud
     * only lets [the registered owner] subscribe. So register at the moment the instance is created:
     *
     * ```kotlin
     * // in the OpenStream handler, ctx is the DispatchContext
     * val streamId = openDevice(...)
     * host.bindEventScope(endpointId, scope = streamId, originRouteId = ctx.routeId)
     * ```
     *
     * [Ownership is proven by the route, not self-declared]. You're saying "it belongs to the caller of the call I'm
     * handling right now", and nervud knows who made that call, so you can't put someone else in,
     * nor put yourself into someone else's event stream.
     *
     * One-way, no receipt. A failure (the endpoint isn't yours, the route has ended) shows up as the subscriber
     * getting NOT_FOUND. So either register [before] returning the handle to the caller, or tell it about the failure;
     * don't leave it holding a stream_id that will never receive events.
     *
     * Registering the same scope again just overwrites it, without an error.
     */
    fun bindEventScope(endpointId: Long, scope: Long, originRouteId: Long) {
        sendEventScope(endpointId, scope, originRouteId, released = false)
    }

    /**
     * Withdraws an instance's ownership. [Must be called] when the instance closes, on both the normal and the error path.
     *
     * nervud cleans up when the connection drops or the endpoint is withdrawn, but that only covers crashes; opening and closing
     * instances repeatedly at run time without withdrawing makes the kernel's ownership table grow without bound.
     *
     * [Send the final event first, then withdraw]. After withdrawing, subscribers no longer receive anything, and "stopped"
     * is exactly the event they need most.
     */
    fun releaseEventScope(endpointId: Long, scope: Long) {
        sendEventScope(endpointId, scope, originRouteId = 0, released = true)
    }

    private fun sendEventScope(
        endpointId: Long,
        scope: Long,
        originRouteId: Long,
        released: Boolean
    ) {
        val writer = frameWriter ?: throw IllegalStateException("not connected")
        writer.writeFrame(
            Envelope.newBuilder()
                .setBindEventScope(
                    BindEventScope.newBuilder()
                        .setEndpointId(endpointId)
                        .setScope(scope)
                        .setOriginRouteId(originRouteId)
                        .setReleased(released)
                        .build()
                )
                .build()
        )
    }

    fun unregisterEndpoint(
        endpointId: Long,
        drain: Boolean = false,
        timeoutMs: Int = 5000
    ): CompletableFuture<Unit> {
        val writer = frameWriter ?: throw IllegalStateException("not connected")
        val requestId = requestIdGenerator.next()
        val future = registerPending.register(requestId)

        try {
            val unregister = UnregisterEndpoint.newBuilder()
                .setRequestId(requestId)
                .setEndpointId(endpointId)
                .setDrain(drain)
                .build()

            writer.writeFrame(
                Envelope.newBuilder().setUnregisterEndpoint(unregister).build()
            )
        } catch (e: Exception) {
            registerPending.failOne(requestId, StatusCode.STATUS_CODE_UNAVAILABLE, "write failed: ${e.message}")
        }

        if (timeoutMs > 0) {
            future.orTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        }

        return future.thenApply { response ->
            if (response.hasFailure()) {
                throw RuntimeException("unregister endpoint rejected: ${response.failure.publicMessage}")
            }
            registrations.entries.removeAll { it.value.endpointId == endpointId }
        }
    }

    /**
     * Makes a method call on an endpoint that has been resolved.
     *
     * # Why a Service needs this too
     *
     * A long-task Provider must be able to [call back into the kernel]: nervud builds the Operation
     * before Dispatch, and after the Provider replies ACCEPTED, the real final state has to be reported through
     * CompleteOperation on `nervus.interface.operation.control`.
     * Without this method, a Service written in Kotlin could only hold the IPC request synchronously until done,
     * which is exactly what returns_operation is meant to avoid.
     *
     * It's not only about operations: before this, no Service could call any other interface
     * (read configuration, query the resource directory).
     */
    fun call(
        endpointId: Long,
        methodId: Int,
        payload: ByteArray = ByteArray(0),
        timeoutMs: Int = handshakeResult?.limits?.defaultTimeoutMs ?: 5000
    ): CompletableFuture<Response> {
        val writer = frameWriter ?: throw IllegalStateException("not connected")
        val limits = handshakeResult?.limits
        val effectiveTimeout =
            if (limits != null && limits.maxTimeoutMs > 0) {
                timeoutMs.coerceAtMost(limits.maxTimeoutMs)
            } else {
                timeoutMs
            }
        val requestId = requestIdGenerator.next()
        val future = callPending.register(requestId, payload.size)

        try {
            writer.writeFrame(
                Envelope.newBuilder().setRequest(
                    Request.newBuilder()
                        .setRequestId(requestId)
                        .setEndpointId(endpointId)
                        .setMethodId(methodId)
                        .setTimeoutMs(effectiveTimeout)
                        .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
                        .build()
                ).build()
            )
            if (effectiveTimeout > 0) {
                future.orTimeout(effectiveTimeout.toLong(), TimeUnit.MILLISECONDS)
            }
        } catch (e: Exception) {
            callPending.failOne(
                requestId, StatusCode.STATUS_CODE_UNAVAILABLE, "write failed: ${e.message}"
            )
        }
        return future
    }

    /**
     * Resolves an interface, giving an endpoint_id usable with [call].
     *
     * [No ResourceSelector]. The only use on the Service side so far is resolving the kernel's built-in interfaces
     * (operation.control and the like), which aren't bound to any resource. An interface bound to a resource with an empty
     * selector is refused by the kernel (v2 has no implicit default); when needed, fill in the parameters following NervusClient's
     * signature, rather than leaving an unused slot now.
     */
    fun resolveEndpoint(
        interfaceId: String,
        minInterfaceMajor: Int = 1,
        maxInterfaceMajor: Int = 1,
        timeoutMs: Int = handshakeResult?.limits?.defaultTimeoutMs ?: 5000
    ): CompletableFuture<Response> {
        val writer = frameWriter ?: throw IllegalStateException("not connected")
        val requestId = requestIdGenerator.next()
        val future = resolvePending.register(requestId)

        try {
            writer.writeFrame(
                Envelope.newBuilder().setResolveEndpoint(
                    ResolveEndpoint.newBuilder()
                        .setRequestId(requestId)
                        .setInterfaceId(interfaceId)
                        .setMinInterfaceMajor(minInterfaceMajor)
                        .setMaxInterfaceMajor(maxInterfaceMajor)
                        .build()
                ).build()
            )
            if (timeoutMs > 0) {
                future.orTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            }
        } catch (e: Exception) {
            resolvePending.failOne(
                requestId, StatusCode.STATUS_CODE_UNAVAILABLE, "write failed: ${e.message}"
            )
        }
        return future
    }

    private suspend fun runPingLoop(idleTimeoutMs: Int) {
        val interval = idleTimeoutMs.toLong() / 2
        while (udSocket?.isOpen == true && isConnected) {
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
        for (reg in registrations.values) {
            try {
                frameWriter?.writeFrame(
                    Envelope.newBuilder().setUnregisterEndpoint(
                        UnregisterEndpoint.newBuilder()
                            .setRequestId(requestIdGenerator.next())
                            .setEndpointId(reg.endpointId)
                            .build()
                    ).build()
                )
            } catch (_: Exception) {
            }
        }
        isConnected = false
        udSocket?.close()
        readerJob?.cancel()
        pingJob?.cancel()
        scope?.cancel()
        registerPending.failAll()
        // All three pending tables must be failed: miss any one and those futures hang forever,
        // and the caller sees "the call neither returned nor failed", far harder to debug than a clear UNAVAILABLE
        callPending.failAll()
        resolvePending.failAll()
        dispatchHandler.clear()
        registrations.clear()
    }

    private suspend fun runReaderLoop() {
        try {
            while (udSocket?.isOpen == true && isConnected) {
                val envelope = try {
                    frameReader?.readFrame()
                } catch (e: java.net.SocketTimeoutException) {
                    logger.fine("read timeout, checking connection")
                    continue
                } ?: break
                when (envelope.bodyCase) {
                    Envelope.BodyCase.REGISTER_ENDPOINT_RESULT -> {
                        handleRegisterResult(envelope.registerEndpointResult)
                    }
                    Envelope.BodyCase.UNREGISTER_ENDPOINT_RESULT -> {
                        handleUnregisterResult(envelope.unregisterEndpointResult)
                    }
                    // Replies to calls the Service initiated.
                    //
                    // [Without these two branches the connection gets closed]: the else below treats any
                    // unhandled body as a protocol violation and calls closeConnection(). So before
                    // a Service can send Requests, this has to recognise their replies.
                    Envelope.BodyCase.RESPONSE -> {
                        handleResponse(envelope.response)
                    }
                    Envelope.BodyCase.RESOLVE_ENDPOINT_RESULT -> {
                        handleResolveEndpointResult(envelope.resolveEndpointResult)
                    }
                    Envelope.BodyCase.DISPATCH -> {
                        handleDispatch(envelope.dispatch)
                    }
                    Envelope.BodyCase.CANCEL_DISPATCH -> {
                        handleCancelDispatch(envelope.cancelDispatch)
                    }
                    Envelope.BodyCase.PING -> {
                        handlePing(envelope.ping)
                    }
                    Envelope.BodyCase.PONG -> {}
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

    private fun handleRegisterResult(result: RegisterEndpointResult) {
        when (result.outcomeCase) {
            RegisterEndpointResult.OutcomeCase.SUCCESS -> {
                val success = result.success
                registerPending.complete(result.requestId, Response.newBuilder()
                    .setSuccess(Success.newBuilder()
                        .setCode(StatusCode.STATUS_CODE_OK)
                        .setPayload(success.toByteString())
                        .build())
                    .build())
            }
            RegisterEndpointResult.OutcomeCase.FAILURE -> {
                registerPending.complete(result.requestId, Response.newBuilder()
                    .setFailure(result.failure)
                    .build())
            }
            else -> {
                logger.warning("register_endpoint_result with no outcome")
                registerPending.complete(result.requestId, Response.newBuilder()
                    .setFailure(Failure.newBuilder()
                        .setCode(StatusCode.STATUS_CODE_INTERNAL)
                        .setPublicMessage("register_endpoint_result with no outcome")
                        .build())
                    .build())
            }
        }
    }

    private fun handleUnregisterResult(result: UnregisterEndpointResult) {
        when (result.outcomeCase) {
            UnregisterEndpointResult.OutcomeCase.SUCCESS -> {
                registerPending.complete(result.requestId, Response.newBuilder()
                    .setSuccess(Success.newBuilder()
                        .setCode(StatusCode.STATUS_CODE_OK)
                        .build())
                    .build())
            }
            UnregisterEndpointResult.OutcomeCase.FAILURE -> {
                registerPending.complete(result.requestId, Response.newBuilder()
                    .setFailure(result.failure)
                    .build())
            }
            else -> {
                logger.warning("unregister_endpoint_result with no outcome")
                registerPending.complete(result.requestId, Response.newBuilder()
                    .setFailure(Failure.newBuilder()
                        .setCode(StatusCode.STATUS_CODE_INTERNAL)
                        .setPublicMessage("unregister_endpoint_result with no outcome")
                        .build())
                    .build())
            }
        }
    }

    /**
     * A reply arrived for a Request the Service initiated.
     *
     * Validation matches NervusClient.handleResponse: Success only accepts OK / ACCEPTED,
     * Failure doesn't accept UNSPECIFIED / OK / ACCEPTED. Invalid ones are dropped instead of completing the
     * future: completing it with a self-contradictory response would make the caller take it as true.
     */
    private fun handleResponse(response: Response) {
        if (response.hasSuccess()) {
            val code = response.success.code
            if (code != StatusCode.STATUS_CODE_OK && code != StatusCode.STATUS_CODE_ACCEPTED) {
                logger.severe("invalid success code: $code")
                return
            }
        } else if (response.hasFailure()) {
            val code = response.failure.code
            if (code == StatusCode.STATUS_CODE_UNSPECIFIED ||
                code == StatusCode.STATUS_CODE_OK ||
                code == StatusCode.STATUS_CODE_ACCEPTED
            ) {
                logger.severe("invalid failure code: $code")
                return
            }
        } else {
            logger.warning("response with no outcome")
            return
        }
        callPending.complete(response.requestId, response)
    }

    /**
     * A reply arrived for a ResolveEndpoint the Service initiated.
     *
     * No endpoint cache: Services resolve far less often than Apps, and a cache would have to be invalidated on
     * EndpointDied / EndpointRevoked; that bookkeeping isn't worth it here, and the caller
     * can just hold the endpoint_id.
     */
    private fun handleResolveEndpointResult(result: ResolveEndpointResult) {
        when (result.outcomeCase) {
            ResolveEndpointResult.OutcomeCase.SUCCESS -> {
                resolvePending.complete(
                    result.requestId,
                    Response.newBuilder()
                        .setSuccess(
                            Success.newBuilder()
                                .setCode(StatusCode.STATUS_CODE_OK)
                                .setPayload(result.success.toByteString())
                                .build()
                        )
                        .build()
                )
            }
            ResolveEndpointResult.OutcomeCase.FAILURE -> {
                resolvePending.complete(
                    result.requestId,
                    Response.newBuilder().setFailure(result.failure).build()
                )
            }
            else -> {
                logger.warning("resolve_endpoint_result with no outcome")
                resolvePending.complete(
                    result.requestId,
                    Response.newBuilder()
                        .setFailure(
                            Failure.newBuilder()
                                .setCode(StatusCode.STATUS_CODE_INTERNAL)
                                .setPublicMessage("resolve_endpoint_result with no outcome")
                                .build()
                        )
                        .build()
                )
            }
        }
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

    private fun handleCancelDispatch(cancelDispatch: CancelDispatch) {
        dispatchHandler.cancelDispatch(cancelDispatch.routeId)
        logger.info("cancel dispatch received for route_id=${cancelDispatch.routeId}, reason=${cancelDispatch.reason}")
    }

    private fun handlePing(ping: Ping) {
        val pong = Pong.newBuilder().setNonce(ping.nonce).build()
        try {
            frameWriter?.writeFrame(
                Envelope.newBuilder().setPong(pong).build()
            )
        } catch (e: Exception) {
            logger.fine("failed to write pong: ${e.message}")
        }
    }

    override fun close() {
        closeConnection()
    }
}
