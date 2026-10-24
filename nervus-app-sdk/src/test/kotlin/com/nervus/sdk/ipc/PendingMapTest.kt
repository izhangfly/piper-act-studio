package com.nervus.sdk.ipc

import com.nervus.sdk.ipc.rpc.PendingMap
import io.github.nervusos.ipc.v1.Response
import io.github.nervusos.ipc.v1.StatusCode
import io.github.nervusos.ipc.v1.Success
import kotlin.test.Test
import kotlin.test.*

class PendingMapTest {

    @Test
    fun `register and complete`() {
        val map = PendingMap()
        val future = map.register(1L)
        assertFalse(future.isDone)

        val response = Response.newBuilder()
            .setRequestId(1L)
            .setSuccess(Success.newBuilder()
                .setCode(StatusCode.STATUS_CODE_OK)
                .build())
            .build()

        val completed = map.complete(1L, response)
        assertTrue(completed)
        assertTrue(future.isDone)
        assertEquals(response, future.get())
    }

    @Test
    fun `complete unknown request returns false`() {
        val map = PendingMap()
        val response = Response.getDefaultInstance()
        assertFalse(map.complete(99L, response))
    }

    @Test
    fun `duplicate registration throws`() {
        val map = PendingMap()
        map.register(1L)
        assertFailsWith<IllegalStateException> {
            map.register(1L)
        }
    }

    @Test
    fun `cancel pending request`() {
        val map = PendingMap()
        val future = map.register(1L)
        assertTrue(map.cancelOne(1L))
        assertTrue(future.isCancelled)
    }

    @Test
    fun `cancel unknown returns false`() {
        val map = PendingMap()
        assertFalse(map.cancelOne(99L))
    }

    @Test
    fun `failAll completes all with UNAVAILABLE`() {
        val map = PendingMap()
        val futures = (1L..5L).map { map.register(it) }

        map.failAll()

        assertEquals(0, map.size())
        futures.forEach { future ->
            assertTrue(future.isDone)
            val response = future.get()
            assertTrue(response.hasFailure())
            assertEquals(
                StatusCode.STATUS_CODE_UNAVAILABLE.number,
                response.failure.codeValue
            )
        }
    }

    @Test
    fun `failAll after partial completion`() {
        val map = PendingMap()
        val f1 = map.register(1L)
        val f2 = map.register(2L)

        map.complete(
            1L,
            Response.newBuilder()
                .setSuccess(Success.newBuilder()
                    .setCode(StatusCode.STATUS_CODE_OK)
                    .build())
                .build()
        )

        map.failAll()

        assertTrue(f1.isDone)
        assertEquals(StatusCode.STATUS_CODE_OK, f1.get().success.code)
        assertTrue(f2.isDone)
        assertEquals(StatusCode.STATUS_CODE_UNAVAILABLE.number, f2.get().failure.codeValue)
    }
}
