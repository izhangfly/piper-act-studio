package com.nervus.sdk.ipc.dispatch

import io.github.nervusos.ipc.v1.*

internal data class DispatchContext(
    val routeId: Long,
    val remainingMs: Int,
    val caller: CallerContext,
    /**
     * The execution context nervud attaches to every Dispatch. [Always] attached from v2 on.
     *
     * A long task's operation_id is in here. nervud allocates it and owns its state machine, and
     * a Provider [must not] make one up: an invented handle wouldn't match nervud's records,
     * and neither side would report an error; cancelling would just never work and progress would never arrive.
     */
    val executionContext: ExecutionContext = ExecutionContext.getDefaultInstance()
) {
    val isCancelled: Boolean get() = routeId in cancelledRouteIds

    /**
     * The ID of the long task this call belongs to; 0 for methods that aren't long tasks.
     *
     * Only meaningful for methods with returns_operation = true in method_meta: nervud builds the Operation
     * before Dispatch and puts its id in the ExecutionContext, so
     * it already exists when the handler receives it and can be passed straight to accept / report / complete.
     */
    val operationId: Long get() = executionContext.operationId

    internal var cancelledRouteIds: Set<Long> = emptySet()
}

internal fun interface MethodHandler {
    fun handle(payload: ByteArray, context: DispatchContext): DispatchResult
}

internal class DispatchHandler {
    private val handlers = HashMap<Int, MethodHandler>()
    private val cancelledRoutes = HashSet<Long>()

    fun register(methodId: Int, handler: MethodHandler) {
        handlers[methodId] = handler
    }

    fun unregister(methodId: Int) {
        handlers.remove(methodId)
    }

    fun cancelDispatch(routeId: Long) {
        cancelledRoutes.add(routeId)
    }

    fun dispatch(dispatch: Dispatch): DispatchResult {
        val handler = handlers[dispatch.methodId]
        if (handler == null) {
            java.util.logging.Logger.getLogger("com.nervus.sdk.ipc.dispatch.DispatchHandler")
                .warning("no handler for method_id=${dispatch.methodId}; registered=${handlers.keys.sorted()}")
            return DispatchResult.newBuilder()
                .setRouteId(dispatch.routeId)
                .setFailure(Failure.newBuilder()
                    .setCode(StatusCode.STATUS_CODE_NOT_FOUND)
                    .setPublicMessage("unknown method_id: ${dispatch.methodId}")
                    .build())
                .build()
        }
        val ctx = DispatchContext(
            routeId = dispatch.routeId,
            remainingMs = dispatch.remainingMs,
            caller = dispatch.caller,
            executionContext = dispatch.executionContext
        ).apply { cancelledRouteIds = cancelledRoutes.toSet() }
        if (ctx.isCancelled) {
            cancelledRoutes.remove(dispatch.routeId)
            return DispatchResult.newBuilder()
                .setRouteId(dispatch.routeId)
                .setFailure(Failure.newBuilder()
                    .setCode(StatusCode.STATUS_CODE_CANCELLED)
                    .setPublicMessage("dispatch cancelled")
                    .build())
                .build()
        }
        val result = handler.handle(dispatch.payload.toByteArray(), ctx)
        cancelledRoutes.remove(dispatch.routeId)
        return result.toBuilder().setRouteId(dispatch.routeId).build()
    }

    fun clear() {
        handlers.clear()
        cancelledRoutes.clear()
    }
}
