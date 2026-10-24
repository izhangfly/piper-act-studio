package com.nervus.sdk.runtime

import com.nervus.sdk.annotations.Method
import com.nervus.sdk.ipc.dispatch.DispatchHandler
import com.nervus.sdk.ipc.dispatch.MethodHandler
import com.nervus.sdk.operation.CurrentCaller
import com.nervus.sdk.operation.CurrentOperation
import com.nervus.sdk.operation.OperationPending
import io.github.nervusos.ipc.v1.CallerContext
import io.github.nervusos.ipc.v1.DispatchResult
import io.github.nervusos.ipc.v1.Failure
import io.github.nervusos.ipc.v1.StatusCode
import io.github.nervusos.ipc.v1.Success
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.runBlocking

/**
 * The result of one handler call.
 *
 * Two branches rather than a plain ByteArray: a long task's ACCEPTED requires an empty payload, and "OK with an empty
 * payload" and "ACCEPTED" mean completely different things on the wire. The first tells the caller
 * the method is done; the second says the result comes later through operation events. Using one empty array
 * for both would leave guessing from state elsewhere which code to send.
 */
internal sealed interface InvokeResult {
    /** Ordinary method: completes at once, with a typed response payload. */
    data class Payload(val bytes: ByteArray) : InvokeResult

    /** Long task: replies ACCEPTED at once; the Provider reports the final state later through operation.control. */
    data object Pending : InvokeResult
}

internal data class MethodRegistration(
    val methodId: Int,
    val methodName: String,
    val isSuspend: Boolean,
    val invoke: (ByteArray) -> InvokeResult,
)

internal class ServiceStub {

    private val registrations = ConcurrentHashMap<Int, MethodRegistration>()

    fun registerInterface(
        instance: Any,
        handler: DispatchHandler,
        dispatchCallback: ((methodId: Int, payload: ByteArray, caller: CallerContext) -> DispatchResult)? = null
    ) {
        val methods = instance::class.java.methods
        val seenIds = HashSet<Int>()
        for (method in methods) {
            val annotation = resolveMethodAnnotation(method) ?: continue
            val methodId = annotation.id
            val allParamTypes = method.parameterTypes

            if (!seenIds.add(methodId)) {
                throw IllegalArgumentException(
                    "duplicate @Method(id=$methodId) on '${method.name}' " +
                    "in class '${instance::class.java.name}'"
                )
            }

            val isSuspend = allParamTypes.lastOrNull()
                ?.let { Continuation::class.java.isAssignableFrom(it) } == true

            val appParamTypes = if (isSuspend) {
                allParamTypes.dropLast(1).toTypedArray()
            } else {
                allParamTypes
            }

            val registration = MethodRegistration(
                methodId = methodId,
                methodName = method.name,
                isSuspend = isSuspend,
                invoke = { payload ->
                    val resolvedArgs = resolveArguments(payload, appParamTypes)
                    try {
                        val result = if (isSuspend) {
                            runBlocking {
                                suspendCoroutine { cont ->
                                    try {
                                        val r = method.invoke(instance, *resolvedArgs, cont)
                                        if (r !== COROUTINE_SUSPENDED) {
                                            cont.resume(r)
                                        }
                                    } catch (e: InvocationTargetException) {
                                        cont.resumeWithException(e.cause ?: e)
                                    } catch (e: Exception) {
                                        cont.resumeWithException(e)
                                    }
                                }
                            }
                        } else {
                            method.invoke(instance, *resolvedArgs)
                        }
                        serializeResult(result)
                    } catch (e: InvocationTargetException) {
                        throw e.cause ?: e
                    }
                }
            )
            registrations[methodId] = registration
            java.util.logging.Logger.getLogger(ServiceStub::class.java.name)
                .info("registered method id=$methodId name=${method.name} suspend=$isSuspend class=${instance::class.java.name}")

            handler.register(methodId, MethodHandler { payload, context ->
                // Bind operation_id to the current thread so the handler can get it with currentOperationId().
                // The handler signature only has room for the request message, with no parameter to carry this
                // fact supplied by the kernel; see CurrentOperation.
                //
                // Both dispatch paths must bind it: a long-task method shouldn't lose its own operation_id
                // just because the host used dispatchCallback.
                CurrentOperation.with(context.operationId) {
                    CurrentCaller.with(context.caller) {
                        if (dispatchCallback != null) {
                            val result = dispatchCallback(methodId, payload, context.caller)
                            result.toBuilder().setRouteId(context.routeId).build()
                        } else {
                            invokeDirect(methodId, payload, context.routeId)
                        }
                    }
                }
            })
        }
        if (registrations.isEmpty()) {
            java.util.logging.Logger.getLogger(ServiceStub::class.java.name)
                .warning("no @Method found on ${instance::class.java.name} (checked interfaces too)")
        }
    }

    /** @Method may live on the interface; Kotlin does not copy it onto the impl method. */
    private fun resolveMethodAnnotation(method: java.lang.reflect.Method): Method? {
        method.getAnnotation(Method::class.java)?.let { return it }
        val paramTypes = method.parameterTypes
        for (iface in method.declaringClass.interfaces) {
            try {
                val m = iface.getMethod(method.name, *paramTypes)
                m.getAnnotation(Method::class.java)?.let { return it }
            } catch (_: NoSuchMethodException) {
            }
        }
        var superCls = method.declaringClass.superclass
        while (superCls != null && superCls != Any::class.java) {
            try {
                val m = superCls.getMethod(method.name, *paramTypes)
                m.getAnnotation(Method::class.java)?.let { return it }
            } catch (_: NoSuchMethodException) {
            }
            for (iface in superCls.interfaces) {
                try {
                    val m = iface.getMethod(method.name, *paramTypes)
                    m.getAnnotation(Method::class.java)?.let { return it }
                } catch (_: NoSuchMethodException) {
                }
            }
            superCls = superCls.superclass
        }
        return null
    }

    private fun invokeDirect(methodId: Int, payload: ByteArray, routeId: Long): DispatchResult {
        val reg = registrations[methodId]
        if (reg == null) {
            return DispatchResult.newBuilder()
                .setRouteId(routeId)
                .setFailure(Failure.newBuilder()
                    .setCode(StatusCode.STATUS_CODE_NOT_FOUND)
                    .setPublicMessage("no handler for method_id=$methodId")
                    .build())
                .build()
        }
        return try {
            DispatchResult.newBuilder()
                .setRouteId(routeId)
                .setSuccess(successOf(reg.invoke(payload)))
                .build()
        } catch (e: Exception) {
            DispatchResult.newBuilder()
                .setRouteId(routeId)
                .setFailure(Failure.newBuilder()
                    .setCode(StatusCode.STATUS_CODE_INTERNAL)
                    .setPublicMessage("method '${reg.methodName}' failed: ${e.message}")
                    .build())
                .build()
        }
    }

    /**
     * Projects the result of one handler call onto the wire's Success.
     *
     * ACCEPTED [carries no payload]: for an operation that's ACCEPTED, the kernel requires an empty
     * Success.payload and treats a non-empty one as a Provider violation, since it means the Provider tried to make
     * its own operation handle, which would give the caller a number nervud doesn't know, and cancel and
     * subscribe would silently fail. The real handle is wrapped by nervud from the ExecutionContext.
     */
    private fun successOf(result: InvokeResult): Success = when (result) {
        is InvokeResult.Payload ->
            Success.newBuilder()
                .setCode(StatusCode.STATUS_CODE_OK)
                .setPayload(com.google.protobuf.ByteString.copyFrom(result.bytes))
                .build()

        InvokeResult.Pending ->
            Success.newBuilder()
                .setCode(StatusCode.STATUS_CODE_ACCEPTED)
                .build()
    }

    fun dispatch(methodId: Int, payload: ByteArray, caller: CallerContext): DispatchResult? {
        val reg = registrations[methodId] ?: return null
        return try {
            DispatchResult.newBuilder()
                .setSuccess(successOf(reg.invoke(payload)))
                .build()
        } catch (e: Exception) {
            DispatchResult.newBuilder()
                .setFailure(Failure.newBuilder()
                    .setCode(StatusCode.STATUS_CODE_INTERNAL)
                    .setPublicMessage("method '${reg.methodName}' failed: ${e.message}")
                    .build())
                .build()
        }
    }

    fun unregisterAll(handler: DispatchHandler) {
        for (methodId in registrations.keys) {
            handler.unregister(methodId)
        }
        registrations.clear()
    }

    internal fun resolveArguments(payload: ByteArray, paramTypes: Array<Class<*>>): Array<Any?> {
        if (paramTypes.isEmpty()) return emptyArray()
        if (paramTypes.size == 1) {
            val type = paramTypes[0]
            if (type == ByteArray::class.java) return arrayOf(payload)
            if (com.google.protobuf.Message::class.java.isAssignableFrom(type)) {
                val parseFrom = type.getMethod("parseFrom", ByteArray::class.java)
                return arrayOf(parseFrom.invoke(null, payload))
            }
        }
        val args = mutableListOf<Any?>()
        var offset = 0
        for (paramType in paramTypes) {
            if (offset + 4 > payload.size) {
                args.add(null)
                break
            }
            val len = bytesToInt(payload, offset)
            offset += 4
            val dataEnd = (offset + len).coerceAtMost(payload.size)
            val data = payload.copyOfRange(offset, dataEnd)
            offset = dataEnd
            when {
                paramType == ByteArray::class.java -> args.add(data)
                paramType == String::class.java -> args.add(String(data))
                paramType == Int::class.java || paramType == Int::class.javaPrimitiveType -> args.add(bytesToInt(data, 0))
                paramType == Long::class.java || paramType == Long::class.javaPrimitiveType -> args.add(bytesToLong(data, 0))
                com.google.protobuf.Message::class.java.isAssignableFrom(paramType) -> {
                    val parseFrom = paramType.getMethod("parseFrom", ByteArray::class.java)
                    args.add(parseFrom.invoke(null, data))
                }
                else -> args.add(data)
            }
        }
        return args.toTypedArray()
    }

    internal fun serializeResult(result: Any?): InvokeResult {
        // Check OperationPending first: it's a "report later" marker, not a payload; falling into
        // any branch below would encode it as data (the else branch would toString it to an object address)
        if (result is OperationPending) return InvokeResult.Pending
        val bytes = when (result) {
            is ByteArray -> result
            is com.google.protobuf.Message -> result.toByteArray()
            is String -> result.toByteArray()
            null -> ByteArray(0)
            else -> result.toString().toByteArray()
        }
        return InvokeResult.Payload(bytes)
    }

    private fun bytesToInt(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
    }

    private fun bytesToLong(data: ByteArray, offset: Int): Long {
        if (offset + 8 > data.size) return 0L
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (data[offset + i].toLong() and 0xFF)
        }
        return result
    }
}
