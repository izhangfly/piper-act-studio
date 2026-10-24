package com.nervus.sdk.ipc.rpc

import io.github.nervusos.ipc.v1.Failure
import io.github.nervusos.ipc.v1.Response
import io.github.nervusos.ipc.v1.StatusCode
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class PendingMap {
    private val map = ConcurrentHashMap<Long, CompletableFuture<Response>>()
    private val payloadBytes = ConcurrentHashMap<Long, Int>()
    @Volatile
    var totalPayloadBytes: Long = 0
        private set

    fun register(requestId: Long, payloadByteCount: Int = 0): CompletableFuture<Response> {
        val future = CompletableFuture<Response>()
        val existing = map.putIfAbsent(requestId, future)
        if (existing != null) {
            throw IllegalStateException("duplicate request_id: $requestId")
        }
        if (payloadByteCount > 0) {
            payloadBytes[requestId] = payloadByteCount
            totalPayloadBytes += payloadByteCount
        }
        return future
    }

    fun complete(requestId: Long, response: Response): Boolean {
        val future = map.remove(requestId)
        payloadBytes.remove(requestId)?.let { totalPayloadBytes -= it }
        return future?.complete(response) ?: false
    }

    fun cancelOne(requestId: Long): Boolean {
        val future = map.remove(requestId)
        payloadBytes.remove(requestId)?.let { totalPayloadBytes -= it }
        return future?.cancel(false) ?: false
    }

    fun failOne(requestId: Long, statusCode: StatusCode = StatusCode.STATUS_CODE_UNAVAILABLE, message: String = "request failed"): Boolean {
        val future = map.remove(requestId)
        payloadBytes.remove(requestId)?.let { totalPayloadBytes -= it }
        if (future == null) return false
        val failure = Failure.newBuilder()
            .setCode(statusCode)
            .setPublicMessage(message)
            .build()
        val response = Response.newBuilder()
            .setFailure(failure)
            .build()
        return future.complete(response)
    }

    fun failAll() {
        val failure = Failure.newBuilder()
            .setCode(StatusCode.STATUS_CODE_UNAVAILABLE)
            .setPublicMessage("connection closed")
            .build()
        val response = Response.newBuilder()
            .setFailure(failure)
            .build()

        val iter = map.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            iter.remove()
            entry.value.complete(response)
        }
        payloadBytes.clear()
        totalPayloadBytes = 0
    }

    fun size(): Int = map.size
}
