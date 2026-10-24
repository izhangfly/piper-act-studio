package com.nervus.sdk.component

import com.nervus.sdk.annotations.Interface
import com.nervus.sdk.ipc.ConnectionState
import com.nervus.sdk.ipc.NervusCallException
import com.nervus.sdk.ipc.NervusClient
import com.nervus.sdk.ipc.describeFailure
import com.nervus.sdk.operation.*
import com.nervus.sdk.permission.PermissionSelf
import com.nervus.sdk.permission.PermissionUi
import com.nervus.sdk.runtime.InterfaceProxy
import com.nervus.sdk.runtime.ResolvedEndpoint
import com.nervus.sdk.runtime.ServiceStub
import io.github.nervusos.iface.operation.v1.*
import io.github.nervusos.iface.permission.v1.CheckSelfPermissionRequest
import io.github.nervusos.iface.permission.v1.CheckSelfPermissionResult
import io.github.nervusos.iface.permission.v1.RequestPermissionOutcome
import io.github.nervusos.iface.permission.v1.RequestPermissionRequest
import io.github.nervusos.iface.permission.v1.RequestPermissionResult
import io.github.nervusos.iface.permission.v1.SelfPermissionState
import io.github.nervusos.ipc.v1.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/**
 * A declaration that "I want to use this interface".
 *
 * Interfaces bound to a resource must give their own selection criteria: [resourceType] plus [labels] or
 * [resourceRole]. Leaving them empty doesn't get you a default device; the kernel refuses it (see
 * `NervusClient.resolveEndpoint`).
 */
data class InterfaceRequirement(
    val id: String,
    val minMajor: Int = 1,
    val maxMajor: Int = 1,
    val resourceType: String = "",
    /**
     * A stable role such as "main" / "front".
     *
     * [Prefer [labels]]. A role is a number on one machine and may not keep that name on another
     * board; code that selects by meaning (`nervus.camera.facing=front`) doesn't change when the board does.
     */
    val resourceRole: String = "",
    /** Label filter; all must match (AND). Platform labels use `nervus.*`, OEM-private ones their own package name. */
    val labels: Map<String, String> = emptyMap(),
    /** What to do on several matches. Default REQUIRE_UNIQUE: report an error rather than pick one for you. */
    val selectionPolicy: ResourceSelectionPolicy =
        ResourceSelectionPolicy.RESOURCE_SELECTION_POLICY_UNSPECIFIED,
    val isRequired: Boolean = true,
    /**
     * Whether to resolve it during [NervusApp.start]. Default true.
     *
     * ## When it must be false
     *
     * **When the target is a UI component with `launch_mode: on-demand`.** Resolve is the only trigger for the kernel
     * to start an on-demand component (`endpoint.Resolve` → `EnsureStarted`), so
     * resolving at start means "start the other process the moment this component starts". If the other side is an app
     * with a window, the user sees a UI they never asked for pop up.
     *
     * Settings declaring `permission.ui` is exactly that case: it should only bring that UI up when the user presses
     * "Permissions", not as soon as Settings opens.
     *
     * ## What changes with false
     *
     * Only when it resolves. The declaration still applies: [NervusApp.call] resolves and caches it through
     * `resolveDeclaredEndpoint` on the first call, and undeclared interfaces are always refused.
     * So false relaxes no checks; it just leaves "when to start the other side" to the call site.
     */
    val resolveEagerly: Boolean = true,
)

abstract class NervusApp(
    config: ComponentConfig = ComponentConfig()
) : Component(config) {

    private var client: NervusClient? = null
    private val resolvedEndpoints = ConcurrentHashMap<String, ResolvedEndpoint>()
    private val endpointResolutionLock = Any()
    private val proxyCache = ConcurrentHashMap<Class<*>, Any>()
    private val stubs = mutableListOf<ServiceStub>()

    protected abstract val requiredInterfaces: List<InterfaceRequirement>

    /**
     * The Interfaces this component provides. Empty by default.
     *
     * ## Why an "App" might provide interfaces
     *
     * The kernel used to have [no] IPC for "start some app": `EnsureStarted` could only be triggered by
     * `endpoint.Resolve` starting a `launch_mode: on-demand` component, and
     * `manual` mode had no wire path that could wake it.
     *
     * So an app that could be opened on Nervus had to:
     *   1. have `launch_mode: on-demand` in the manifest
     *   2. declare an interface under `exports` (even one with no methods)
     *   3. RegisterEndpoint after starting
     *
     * Others "started" it by sending ResolveEndpoint to that interface: the kernel found no live
     * provider, started the component, and returned once it had registered. (LaunchComponent now covers this.)
     *
     * For an interface without methods, pass null as [instance]; it only occupies a resolvable name.
     */
    protected open val providedInterfaces: List<ProvidedInterface> = emptyList()

    override fun isActive(): Boolean = client?.connectionState == ConnectionState.CONNECTED

    override fun doStart() {
        client?.close()
        val c = NervusClient(
            sdkName = config.sdkName,
            sdkVersion = config.sdkVersion
        )
        // Drop this layer's cache when an endpoint becomes invalid.
        //
        // [It must be installed before connect]: EndpointDied can arrive as soon as the connection is up, and installing late
        // would miss that one.
        //
        // Without it you get a permanent fault: resolvedEndpoints remembers an endpoint
        // and never invalidates it, and an on-demand Provider [exiting normally] (reaped by the kernel after an idle timeout)
        // turns it into a dead number. Every call after that is NOT_FOUND until this process restarts,
        // when a fresh Resolve could have started that Provider again.
        c.onEndpointInvalidated = { resolvedEndpoints.clear() }
        c.connect(
            socketPath = config.socketPath,
            componentId = config.componentId,
            handshakeTimeoutMs = config.handshakeTimeoutMs
        )
        client = c
        // Register before resolving: registering is "let others wake me", resolving is "I want to use others".
        // The other way round, if resolving blocked on starting some component, this component would be slow to register,
        // and a Resolve waiting on us would time out
        registerProvidedInterfaces(c)
        resolveRequiredInterfaces(c)
    }

    private fun registerProvidedInterfaces(c: NervusClient) {
        for (iface in providedInterfaces) {
            // [Register the local handlers first, then report to nervud].
            //
            // The moment registerEndpoint().get() returns, nervud may hand this
            // endpoint id to a caller waiting on Resolve and immediately deliver a Dispatch.
            // If stub.registerInterface ran after that, the first Dispatch would find
            // an empty handler map and the caller would get NOT_FOUND. It then takes the re-resolve
            // retry path, the log shows Resolve succeeding and only the first call failing with NOT_FOUND,
            // far from the root cause.
            //
            // Registering the handlers locally first, then reporting "I'm ready" to nervud, closes
            // that window.
            val stub = iface.instance?.let { impl ->
                ServiceStub().also { s ->
                    // [No dispatchCallback].
                    //
                    // This used to pass `{ id, p, c -> dispatch(id, p, c) }`, and
                    // ServiceStub's dispatch is either/or: given a callback it [only uses the callback] and never
                    // takes invokeDirect, the @Method-annotation path. So dispatch()'s default
                    // implementation (falling back to NOT_FOUND) bypassed every @Method: a method that registered
                    // successfully never received a call.
                    //
                    // Now the hook is installed only if [the subclass really overrides dispatch]:
                    // dispatchCallback is an optional interceptor, not a replacement.
                    s.registerInterface(impl, c.handler, dispatchInterceptor())
                }
            }
            val endpointId = try {
                c.registerEndpoint(
                    interfaceId = iface.id,
                    interfaceMajor = iface.major,
                    interfaceMinor = iface.minor,
                    schemaHash = iface.schemaHash,
                    resourceHandle = iface.resourceHandle,
                ).get(30, TimeUnit.SECONDS)
            } catch (e: Exception) {
                // A failed registration must not be downgraded to a warning: failing to register means this component can never be woken,
                // and the symptom would be "I tapped the icon and nothing happened", far from the cause. Failing outright makes the
                // supervisor back off and restart, with the exact reason in the log
                throw RuntimeException("failed to register interface '${iface.id}': ${e.message}", e)
            }
            stub?.let { stubs.add(it) }
            logger.info("registered interface '${iface.id}' -> endpoint $endpointId")
        }
    }

    /**
     * The fallback when no method handler matches. Subclasses may override it.
     *
     * Defaults to NOT_FOUND: the protocol's StatusCode has no UNIMPLEMENTED, and
     * UNAVAILABLE means "retry later", which is misleading for a method that doesn't exist at all.
     *
     * # Overriding it means taking over [all] dispatching
     *
     * Once a subclass overrides this method it becomes the single entry point for all of the component's methods, and the
     * `@Method`-annotated ones must be forwarded by it too. If you don't want to take over everything and only want a cross-cutting layer, don't override it:
     * do it inside each `@Method` method, or use [CurrentCaller] to get the caller.
     *
     * [dispatchInterceptor] guarantees this behaviour: the interceptor is only installed when it really is overridden.
     */
    protected open fun dispatch(
        methodId: Int,
        payload: ByteArray,
        context: CallerContext
    ): DispatchResult =
        DispatchResult.newBuilder()
            .setFailure(
                Failure.newBuilder()
                    .setCode(StatusCode.STATUS_CODE_NOT_FOUND)
                    .setPublicMessage("no handler for method_id=$methodId")
                    .build()
            )
            .build()

    /**
     * Returns an interceptor if the subclass overrides [dispatch], otherwise null (so the stub uses annotation dispatch).
     *
     * # Why reflection is needed to decide
     *
     * `ServiceStub` dispatch is either/or: with a callback it only uses the callback. Always passing the callback would let
     * [dispatch]'s default implementation (falling back to NOT_FOUND) swallow every `@Method`: a method that
     * registered successfully but never receives a call, with symptom and root cause very far apart.
     *
     * The test is "is the class declaring dispatch NervusApp itself": if not, it was overridden.
     * That's more reliable than asking subclasses to declare `override val handlesDispatch = true` as well:
     * that would be duplicate information to keep in sync with the override by hand, and forgetting brings this bug back.
     */
    private fun dispatchInterceptor(): ((Int, ByteArray, CallerContext) -> DispatchResult)? {
        // [Walk the inheritance chain yourself with getDeclaredMethod].
        //
        // getMethod only finds public methods, and dispatch is protected, so it would throw
        // NoSuchMethodException and "overridden" would be judged "not overridden". The trap is subtle:
        // the test looks like it works (the not-overridden side gets the right answer), and only the overridden side silently fails.
        var cls: Class<*>? = javaClass
        while (cls != null && cls != NervusApp::class.java) {
            val found = try {
                cls.getDeclaredMethod(
                    "dispatch",
                    Int::class.javaPrimitiveType,
                    ByteArray::class.java,
                    CallerContext::class.java,
                )
            } catch (_: NoSuchMethodException) {
                null
            }
            if (found != null) {
                logger.info("dispatch() overridden by ${cls.name}; it now handles all methods")
                return { methodId, payload, caller -> dispatch(methodId, payload, caller) }
            }
            cls = cls.superclass
        }
        return null
    }

    /**
     * Resolves an interface at run time and returns its endpoint_id.
     *
     * The difference from [requiredInterfaces]: those are resolved all at once at start; this is for when
     * [you only know at run time what you need]. A launcher opening an app is the typical example:
     * which apps are installed is only learned from pkgmanager at run time.
     *
     * For "start an app", the return value usually isn't needed: the call itself is the start
     * (the kernel started that component for it).
     */
    /**
     * Asks the kernel to start an installed component.
     *
     * Needs `perm.system.launch` (MinTrust=Platform). The launcher, session services and the App Store use it.
     *
     * @return true if the component was already running before this request
     */
    /**
     * Acquires a ControlLease on one resource, for calling lease-gated methods (see [ControlLease]).
     *
     * The resource must be named explicitly (protocol v2 has no implicit default): an arm is
     * `nervus.resource.manipulator.arm` with role `arm.main`. HUMAN is the class for a person
     * steering live (teleoperation); it pre-empts AI leases and carries a deadman.
     */
    protected fun acquireControl(
        resourceType: String,
        resourceRole: String,
        controllerClass: ControllerClass = ControllerClass.CONTROLLER_CLASS_HUMAN,
        timeoutSeconds: Long = 5,
    ): ControlLease {
        val c = client ?: throw IllegalStateException("component not started")
        val selector = ResourceSelector.newBuilder().setType(resourceType).setRole(resourceRole).build()
        val success = c.acquireControl(selector, controllerClass).get(timeoutSeconds, TimeUnit.SECONDS)
        return ControlLease(c, selector, controllerClass, success)
    }

    protected fun launchComponent(
        packageId: String,
        componentId: String,
        timeoutSeconds: Long = 30,
    ): Boolean {
        val c = client ?: throw IllegalStateException("component not started")
        return c.launchComponent(packageId, componentId)
            .get(timeoutSeconds, TimeUnit.SECONDS)
    }

    protected fun resolveNow(
        interfaceId: String,
        minMajor: Int = 1,
        maxMajor: Int = 1,
        timeoutSeconds: Long = 30,
    ): Long {
        val c = client ?: throw IllegalStateException("component not started")
        val response = c.resolveEndpoint(
            interfaceId = interfaceId,
            minInterfaceMajor = minMajor,
            maxInterfaceMajor = maxMajor,
        ).get(timeoutSeconds, TimeUnit.SECONDS)
        if (response.hasFailure()) {
            throw RuntimeException("resolve '$interfaceId' failed: ${describeFailure(response.failure)}")
        }
        return ResolveEndpointSuccess.parseFrom(response.success.payload).endpointId
    }

    override fun doClose() {
        client?.let { c -> stubs.forEach { it.unregisterAll(c.handler) } }
        stubs.clear()
        client?.close()
        client = null
        resolvedEndpoints.clear()
        proxyCache.clear()
    }

    /**
     * Subscribes to an event on an interface.
     *
     * [scope] is the instance scope: when one endpoint has several streams / operations open at once,
     * it says which one to watch, and its value is the handle the Provider gave you. If the event is declared scoped it's
     * required and non-zero; otherwise it must be 0, and the kernel refuses either mistake. Subscribing to an
     * instance that isn't yours returns NOT_FOUND.
     */
    protected fun subscribe(
        interfaceClass: KClass<*>,
        eventId: Int,
        payload: ByteArray = ByteArray(0),
        scope: Long = 0
    ): CompletableFuture<Flow<Event>> {
        val c = client ?: throw IllegalStateException("component not started")
        val endpoint = findEndpointForInterface(interfaceClass)
        return c.subscribe(endpoint.endpointId, eventId, payload, scope)
    }

    private fun resolveRequiredInterfaces(c: NervusClient) {
        for (req in requiredInterfaces) {
            // resolveEagerly = false: the declaration still applies; resolving is only delayed until the first call.
            // That's required for on-demand UI components: resolving here would start the other side's
            // process at once, and the user would see a window they never opened (see
            // InterfaceRequirement.resolveEagerly).
            if (!req.resolveEagerly) {
                logger.info("interface '${req.id}' declared for lazy resolution")
                continue
            }
            try {
                resolveDeclaredEndpoint(req.id) { resolveInterface(c, it) }
            } catch (e: Exception) {
                if (req.isRequired) {
                    throw RuntimeException("failed to resolve required interface '${req.id}': ${e.message}", e)
                }
                logger.warning("failed to resolve optional interface '${req.id}': ${e.message}")
            }
        }
    }

    private fun resolveInterface(c: NervusClient, req: InterfaceRequirement): ResolvedEndpoint {
        val response = try {
            c.resolveEndpoint(
                interfaceId = req.id,
                minInterfaceMajor = req.minMajor,
                maxInterfaceMajor = req.maxMajor,
                resourceType = req.resourceType,
                resourceRole = req.resourceRole,
                resourceLabels = req.labels,
                selectionPolicy = req.selectionPolicy,
            ).get(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            throw RuntimeException("resolve request failed: ${e.message}", e)
        }
        if (response.hasFailure()) {
            throw RuntimeException(describeFailure(response.failure))
        }
        val success = ResolveEndpointSuccess.parseFrom(response.success.payload)
        return ResolvedEndpoint(
            endpointId = success.endpointId,
            interfaceId = req.id,
            interfaceMajor = success.interfaceMajor,
            interfaceMinor = success.interfaceMinor,
        ).also { endpoint ->
            logger.info("resolved interface '${req.id}' -> endpoint ${endpoint.endpointId}")
        }
    }

    /**
     * Returns a cached endpoint or resolves a declared interface on demand.
     *
     * An optional interface may be unavailable while components start in parallel. A failed
     * attempt is deliberately not cached, so the next call can resolve it after its provider
     * has registered.
     */
    internal fun resolveDeclaredEndpoint(
        interfaceId: String,
        resolver: (InterfaceRequirement) -> ResolvedEndpoint,
    ): ResolvedEndpoint {
        resolvedEndpoints[interfaceId]?.let { return it }
        val requirement = requiredInterfaces.firstOrNull { it.id == interfaceId }
            ?: throw IllegalStateException(
                "interface '$interfaceId' is not declared in requiredInterfaces"
            )
        synchronized(endpointResolutionLock) {
            resolvedEndpoints[interfaceId]?.let { return it }
            return resolver(requirement).also { resolvedEndpoints[interfaceId] = it }
        }
    }

    /**
     * Makes one call on a resolved interface and returns the raw bytes of the response payload.
     *
     * ## Why offer this instead of always using the [use] dynamic proxy
     *
     * [use] infers return types from method signatures by reflection, but in bytecode a Kotlin `suspend fun` returns
     * `Object` and a `CompletableFuture<T>` returns
     * `CompletableFuture`. **The type argument isn't available at run time**, so InterfaceProxy's
     * cast falls back to returning raw ByteArray, and the caller hits ClassCastException on it.
     *
     * For interfaces whose payload is a protobuf message (all system interfaces are), writing
     * `XxxResult.parseFrom(call(...))` explicitly is both precise and readable, better than letting reflection guess.
     *
     * @param interfaceId must be declared in [requiredInterfaces]; if the optional resolve failed at start,
     * it is resolved again here
     * @param methodId the stable numeric method ID from the interface schema
     */
    protected fun call(
        interfaceId: String,
        methodId: Int,
        payload: ByteArray = ByteArray(0),
        timeoutSeconds: Long = 30,
    ): ByteArray {
        val c = client ?: throw IllegalStateException("component not started")
        val endpoint = resolveDeclaredEndpoint(interfaceId) { resolveInterface(c, it) }
        val response = c.call(endpoint.endpointId, methodId, payload)
            .get(timeoutSeconds, TimeUnit.SECONDS)
        if (response.hasFailure()) {
            // NOT_FOUND has a very common, harmless cause: an on-demand Provider went idle and exited
            // after we resolved it but before the call arrived. Cache invalidation relies on EndpointDied, and that
            // notification races with this call; it may be a few milliseconds late.
            //
            // So drop the cache, resolve once more and retry. Resolving again starts the on-demand
            // Provider again, the call succeeds as usual, and the caller [never sees] this
            // lifecycle detail, which is exactly how on-demand should feel.
            //
            // [Retry only once, and only on NOT_FOUND]: when the method really doesn't exist, the second attempt gets
            // the same NOT_FOUND and throws it as-is, so it never becomes a retry loop.
            if (response.failure.code == StatusCode.STATUS_CODE_NOT_FOUND) {
                // [Log both failures]: the thrown exception may be swallowed by the caller's runCatching
                // and only shown in the UI, which would leave just "resolved -> endpoint N" in the journal,
                // a log without the real error when debugging.
                logger.warning(
                    "call $interfaceId#$methodId on endpoint ${endpoint.endpointId} " +
                        "got NOT_FOUND (${response.failure.publicMessage}); re-resolving"
                )
                resolvedEndpoints.remove(interfaceId)
                val retryEndpoint = resolveDeclaredEndpoint(interfaceId) { resolveInterface(c, it) }
                val retry = c.call(retryEndpoint.endpointId, methodId, payload)
                    .get(timeoutSeconds, TimeUnit.SECONDS)
                if (!retry.hasFailure()) return retry.success.payload.toByteArray()
                logger.severe(
                    "call $interfaceId#$methodId still failed on endpoint " +
                        "${retryEndpoint.endpointId} after re-resolve: " +
                        "code=${retry.failure.code} msg='${retry.failure.publicMessage}'"
                )
                throw NervusCallException(
                    retry.failure.code,
                    "call $interfaceId#$methodId failed after re-resolve: " +
                        "${retry.failure.publicMessage} (code=${retry.failure.code})"
                )
            }
            throw NervusCallException(
                response.failure.code,
                "call $interfaceId#$methodId failed: ${response.failure.publicMessage} " +
                    "(code=${response.failure.code})"
            )
        }
        return response.success.payload.toByteArray()
    }

    // ---- Long tasks (Operations) --------------------------------------------------
    //
    // Both sides are here: the Provider side reports progress and the final state, the caller side starts one and waits for the end.
    //
    // operation.control [doesn't need] to be in requiredInterfaces: it's a built-in interface, present while nervud
    // is alive, and "I use long tasks" is decided by the method contract (returns_operation); it shouldn't need
    // a hand-written declaration in every component. A missing one would show up as "interface not declared",
    // and developers wouldn't even realise they were using it.

    /** Endpoint cache for operation.control. Resolved once, then reused. */
    @Volatile
    private var operationEndpointId: Long = 0

    private fun operationEndpoint(): Long {
        val cached = operationEndpointId
        if (cached != 0L) return cached
        synchronized(endpointResolutionLock) {
            if (operationEndpointId == 0L) {
                operationEndpointId = resolveNow(OperationControl.INTERFACE_ID)
            }
            return operationEndpointId
        }
    }

    private fun callOperationControl(methodId: Int, payload: ByteArray, timeoutSeconds: Long = 30) {
        val c = client ?: throw IllegalStateException("component not started")
        val response = c.call(operationEndpoint(), methodId, payload)
            .get(timeoutSeconds, TimeUnit.SECONDS)
        if (response.hasFailure()) {
            throw RuntimeException(
                "operation.control#$methodId failed: ${response.failure.publicMessage} " +
                    "(code=${response.failure.code})"
            )
        }
    }

    /**
     * [Provider side] Confirms that a long task is starting, moving its state from PENDING to RUNNING.
     *
     * **Must be called before [completeOperation]**: the state machine doesn't allow PENDING straight to
     * SUCCEEDED (a real success always goes through RUNNING), and [reportProgress] is only valid after RUNNING
     * too.
     *
     * @param motionEpoch the current motion epoch for motion tasks; 0 for non-motion tasks.
     */
    protected fun acceptOperation(operationId: Long, motionEpoch: Long = 0) {
        callOperationControl(
            OperationControl.ACCEPT_OPERATION, acceptPayload(operationId, motionEpoch))
    }

    /** [Provider side] Sends one typed progress update. Doesn't change state. */
    protected fun reportProgress(operationId: Long, progress: ByteArray) {
        callOperationControl(
            OperationControl.REPORT_PROGRESS, progressPayload(operationId, progress))
    }

    /**
     * [Provider side] Ends a long task in success.
     *
     * Results like "the user pressed cancel on the confirmation screen" also go here: that's a confirmation that finished normally with
     * the answer "no", and it should end with OK and put the refusal in a field of the result (such as
     * `ConfirmInstallResult.accepted = false`). A failure code would leave the caller unable to tell
     * "the user said no" from "the confirmation screen crashed", and the two deserve completely different reactions in the UI.
     */
    protected fun completeOperation(operationId: Long, result: ByteArray = ByteArray(0)) {
        callOperationControl(
            OperationControl.COMPLETE_OPERATION, completeSuccessPayload(operationId, result))
    }

    /**
     * [Provider side] Ends a long task in failure.
     *
     * code must be a failure reason a Provider may legitimately produce; codes outside that are normalised by nervud
     * to INTERNAL, fail-closed, and audited as a contract violation (PERMISSION_DENIED and
     * UNAUTHENTICATED especially can't be used: authorisation decisions can only come from the kernel).
     */
    protected fun failOperation(
        operationId: Long,
        code: StatusCode = StatusCode.STATUS_CODE_INTERNAL,
        errorDetail: ByteArray = ByteArray(0),
    ) {
        callOperationControl(
            OperationControl.COMPLETE_OPERATION,
            completeFailurePayload(operationId, code, errorDetail))
    }

    /**
     * [Caller side] Calls a long-task method, waits for it to end, and returns the final state.
     *
     * It's written synchronously like a plain [call], with the asynchrony underneath: send the request, get ACCEPTED +
     * operation_id, subscribe to OperationChanged with it as the scope, and return when the final event arrives.
     *
     * # Why check GetOperation after subscribing
     *
     * The operation_id is only known once ACCEPTED comes back, and the Provider may well finish before that
     * (a long task done in a few milliseconds). The final event then happens before the subscription, and subscribing alone
     * would wait forever. One GetOperation closes that race: if it's already finished, return straight away.
     *
     * The opposite order (check, then subscribe) doesn't work: if it isn't finished when checked but finishes just before the
     * subscription is set up, the same window opens again. Subscribing first, then checking, covers both sides.
     *
     * @param timeoutSeconds the upper limit for waiting for the final state. Long tasks can take far longer than ordinary calls,
     * so the default is 5 minutes; tasks that wait on a person, like confirmation screens, should allow more themselves.
     */
    protected fun callOperation(
        interfaceId: String,
        methodId: Int,
        payload: ByteArray = ByteArray(0),
        timeoutSeconds: Long = 300,
        endpointId: Long = 0,
    ): OperationOutcome {
        val c = client ?: throw IllegalStateException("component not started")
        // A non-zero endpointId skips the requiredInterfaces check and uses the given endpoint directly.
        //
        // [Only for the few interfaces inside the SDK that "apps shouldn't have to declare"]: the permission request
        //  (permission.ui#RequestPermission) is in the same position as operation.control:
        // any app might use it, and requiring a hand-written declaration means a missing one shows up as
        // "interface not declared", while the developer doesn't feel they are "using an interface" at all.
        //
        // Default 0 = the normal declaration check; existing call sites behave the same.
        val target = if (endpointId != 0L) {
            endpointId
        } else {
            resolveDeclaredEndpoint(interfaceId) { resolveInterface(c, it) }.endpointId
        }
        val response = c.call(target, methodId, payload, timeoutMs = 0)
            .get(30, TimeUnit.SECONDS)
        if (response.hasFailure()) {
            throw RuntimeException(
                "callOperation $interfaceId#$methodId failed: " +
                    "${response.failure.publicMessage} (code=${response.failure.code})"
            )
        }
        if (response.success.code != StatusCode.STATUS_CODE_ACCEPTED) {
            // The method's method_meta doesn't declare returns_operation, or the Provider finished synchronously.
            // Return it to the caller as a successful immediate completion rather than an error; the result it got is right
            return OperationOutcome(
                state = OperationState.OPERATION_STATE_SUCCEEDED,
                code = response.success.code,
                result = response.success.payload.toByteArray(),
                errorDetail = ByteArray(0),
            )
        }
        val operationId = OperationHandle.parseFrom(response.success.payload).operationId
        if (operationId == 0L) {
            throw RuntimeException("callOperation $interfaceId#$methodId: ACCEPTED without handle")
        }

        val events = c.subscribe(
            endpointId = operationEndpoint(),
            eventId = OperationControl.EVENT_OPERATION_CHANGED,
            scope = operationId,
        ).get(30, TimeUnit.SECONDS)

        // Close the race: it may have finished before the subscription was set up
        val status = OperationStatus.parseFrom(
            run {
                val r = c.call(
                    operationEndpoint(),
                    OperationControl.GET_OPERATION,
                    GetOperationRequest.newBuilder().setOperationId(operationId).build()
                        .toByteArray(),
                ).get(30, TimeUnit.SECONDS)
                if (r.hasFailure()) {
                    throw RuntimeException(
                        "get operation $operationId failed: ${r.failure.publicMessage}")
                }
                r.success.payload
            }
        )
        if (status.state.isTerminal()) {
            return OperationOutcome(
                state = status.state,
                code = status.terminalCode,
                result = status.terminalResult.toByteArray(),
                errorDetail = status.terminalError.toByteArray(),
            )
        }

        return runBlocking {
            withTimeout(timeoutSeconds * 1000) {
                events
                    .map { OperationEvent.parseFrom(it.payload) }
                    .mapNotNull { it.terminalOutcome() }
                    .first()
            }
        }
    }

    // ---- Checking and requesting permissions ------------------------------------
    //
    // An Android-style two-step flow: [before] calling a method that needs a sensitive permission, check; if missing, request.
    //
    //     if (!hasPermission(P)) {
    //         val ok = requestPermission(listOf(P), "to scan a QR code")[P]?.granted == true
    //         if (!ok) return   // the user said no, or it can't be requested at all
    //     }
    //     useCamera()
    //
    // Neither interface [needs] to be in requiredInterfaces, for the same reason as operation.control:
    // any app might use them, and requiring a hand-written declaration means a missing one shows up as
    // "interface not declared", while the developer doesn't feel they are "using an interface" at all.

    /** Endpoint cache for permission.self. Resolved once, then reused. */
    @Volatile
    private var permissionSelfEndpointId: Long = 0

    private fun permissionSelfEndpoint(): Long {
        val cached = permissionSelfEndpointId
        if (cached != 0L) return cached
        synchronized(endpointResolutionLock) {
            if (permissionSelfEndpointId == 0L) {
                permissionSelfEndpointId = resolveNow(PermissionSelf.INTERFACE_ID)
            }
            return permissionSelfEndpointId
        }
    }

    /**
     * Checks your own state for these permissions. Returns a table keyed by permission ID.
     *
     * The equivalent of Android's `checkSelfPermission`, but several at once: an app typically checks every
     * sensitive permission it asked for at start-up, and asking one by one would cost in proportion to the count. A batch also
     * guarantees one answer comes from one Catalog revision.
     *
     * Results are **keyed by permission ID, not by position**: the kernel de-duplicates, so positions wouldn't line up.
     *
     * At most 100 per call (the kernel's limit); more returns INVALID_ARGUMENT.
     *
     * Blocks; don't call it on the UI thread.
     */
    protected fun checkSelfPermission(
        permissionIds: List<String>,
        timeoutSeconds: Long = 30,
    ): Map<String, SelfPermissionState> {
        if (permissionIds.isEmpty()) return emptyMap()
        val c = client ?: throw IllegalStateException("component not started")
        val response = c.call(
            permissionSelfEndpoint(),
            PermissionSelf.CHECK,
            CheckSelfPermissionRequest.newBuilder()
                .addAllPermissionIds(permissionIds)
                .build()
                .toByteArray(),
        ).get(timeoutSeconds, TimeUnit.SECONDS)
        if (response.hasFailure()) {
            throw RuntimeException(
                "checkSelfPermission failed: ${response.failure.publicMessage} " +
                    "(code=${response.failure.code})"
            )
        }
        return CheckSelfPermissionResult.parseFrom(response.success.payload)
            .statesList
            .associateBy { it.permissionId }
    }

    /**
     * Whether this permission can be used right now.
     *
     * Reads `granted`, the kernel's `AllowedAt` conclusion, which already combines "is it in the install-time
     * grant set", "which grant mode" and "is the runtime state GRANTED".
     *
     * **Don't infer it from `state` yourself**: a NORMAL permission's `state` is always
     * UNSPECIFIED, yet it's in effect from install; judging by state would read it as "missing".
     *
     * If the permission can't be found (misspelt, or its defining package was uninstalled), returns false: fail closed.
     */
    protected fun hasPermission(permissionId: String, timeoutSeconds: Long = 30): Boolean =
        checkSelfPermission(listOf(permissionId), timeoutSeconds)[permissionId]?.granted == true

    /**
     * Asks the user for a set of permissions. Returns a result table keyed by permission ID.
     *
     * The equivalent of Android's `requestPermissions`. **It shows a dialog and waits for the user**, so:
     *
     *  - Only call it right after the user did something that needs the permission (pressed "Scan" then ask for the camera).
     *    An app that pops a stack of dialogs at start-up gets them dismissed without thought, which is as good as not asking
     *  - Check [hasPermission] first; if you already have it, don't ask, or you show a dialog for nothing
     *  - Look at `outcome.granted`; don't assume "the call succeeded = got everything": **one request can
     *    partly succeed**, and the user may well allow the camera and refuse the microphone
     *  - Don't retry when `decision == DENIED_PERMANENT`: no dialog will appear and the result won't change.
     *    Point the user to the permission manager (the Permissions entry in Settings)
     *  - Don't retry `decision == NOT_REQUESTABLE` either: this package never asked for it in its manifest,
     *    the install-time decision didn't allow it, or it isn't in the tier the user decides
     *
     * @param rationale one sentence for the user explaining why this feature needs these permissions.
     * Optional but strongly recommended: only the app knows what the user just pressed. It is shown clearly marked as **the app's own**
     * reason and can't pass itself off as a system statement.
     *
     * @param timeoutSeconds the limit for waiting on the user, 10 minutes by default. Human reaction time isn't part of
     * any deadline budget, so this is more generous than for ordinary long tasks.
     *
     * Blocks; don't call it on the UI thread.
     */
    protected fun requestPermission(
        permissionIds: List<String>,
        rationale: String = "",
        timeoutSeconds: Long = 600,
    ): Map<String, RequestPermissionOutcome> {
        require(permissionIds.isNotEmpty()) {
            // An empty list is INVALID_ARGUMENT on the kernel side: a dialog with no entries means nothing
            // to the user. Stop it here so the error stays at the call site instead of after a round trip
            "requestPermission: permissionIds must not be empty"
        }
        // [Resolve every time, no cache]. permissionui is an on-demand UI component that may exit after use,
        // so a cached endpoint can go dead, while Resolve itself is what starts it
        // again. Permission requests are rare and user-triggered, so an extra Resolve doesn't matter;
        // a request failing mysteriously on a dead endpoint would.
        //
        // permission.self can be cached because the kernel implements it: it's there while nervud is alive.
        val endpoint = resolveNow(PermissionUi.INTERFACE_ID)
        val outcome = callOperation(
            interfaceId = PermissionUi.INTERFACE_ID,
            methodId = PermissionUi.REQUEST_PERMISSION,
            payload = RequestPermissionRequest.newBuilder()
                .addAllPermissionIds(permissionIds)
                .setRationale(rationale)
                .build()
                .toByteArray(),
            timeoutSeconds = timeoutSeconds,
            endpointId = endpoint,
        )
        if (!outcome.isSuccess) {
            throw RuntimeException(
                "requestPermission ended in ${outcome.state} (code=${outcome.code})"
            )
        }
        return RequestPermissionResult.parseFrom(outcome.result)
            .outcomesList
            .associateBy { it.permissionId }
    }

    /**
     * "Use it if I have it, ask once if I don't", combining the check and the request in one step.
     *
     * Returns the permissions that are **really usable right now**. The caller decides what it can do from the returned set:
     *
     *     val ok = ensurePermissions(listOf(CAMERA, MIC), "to record video")
     *     if (CAMERA in ok) startPreview()
     *     if (MIC in ok) startAudio()
     *
     * Only the `requestable` ones are requested, so you **won't** get "another dialog for a permission doomed
     * to fail" or "an endless retry on false"; that's why the `requestable`
     * field exists.
     *
     * When nothing needs requesting no dialog appears and permission.ui isn't resolved (so
     * permissionui isn't started).
     *
     * Blocks; don't call it on the UI thread.
     */
    protected fun ensurePermissions(
        permissionIds: List<String>,
        rationale: String = "",
        timeoutSeconds: Long = 600,
    ): Set<String> {
        if (permissionIds.isEmpty()) return emptySet()
        val states = checkSelfPermission(permissionIds)

        val granted = states.values.filter { it.granted }.map { it.permissionId }.toMutableSet()
        val toAsk = states.values.filter { !it.granted && it.requestable }.map { it.permissionId }
        if (toAsk.isEmpty()) return granted

        for (outcome in requestPermission(toAsk, rationale, timeoutSeconds).values) {
            if (outcome.granted) granted += outcome.permissionId
        }
        return granted
    }

    @Suppress("UNCHECKED_CAST")
    protected fun <T : Any> use(interfaceClass: KClass<T>): T {
        val endpoint = findEndpointForInterface(interfaceClass)
        val cached = proxyCache[interfaceClass.java]
        if (cached != null) return cached as T
        val proxy = InterfaceProxy(
            client ?: throw IllegalStateException("component not started"),
            endpoint
        )
        val instance = proxy.create(interfaceClass)
        proxyCache[interfaceClass.java] = instance
        return instance
    }

    private fun <T : Any> findEndpointForInterface(interfaceClass: KClass<T>): ResolvedEndpoint {
        val interfaceId = resolveInterfaceId(interfaceClass)
        val c = client ?: throw IllegalStateException("component not started")
        return try {
            resolveDeclaredEndpoint(interfaceId) { resolveInterface(c, it) }
        } catch (e: IllegalStateException) {
            throw IllegalStateException(
                "interface '${interfaceClass.qualifiedName}' is not declared in " +
                    "requiredInterfaces with a matching 'id' field.",
                e,
            )
        }
    }

    private fun resolveInterfaceId(interfaceClass: KClass<*>): String {
        val annotation = interfaceClass.annotations.filterIsInstance<Interface>().firstOrNull()
        val customId = annotation?.id?.takeIf { it.isNotEmpty() }
        return customId
            ?: interfaceClass.qualifiedName
            ?: interfaceClass.simpleName
            ?: "unknown"
    }
}
