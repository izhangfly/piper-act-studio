package com.nervus.sdk.runtime

import com.nervus.sdk.ipc.dispatch.DispatchHandler
import com.nervus.sdk.operation.OperationPending
import io.github.nervusos.ipc.v1.CallerContext
import io.github.nervusos.ipc.v1.DispatchResult
import io.github.nervusos.ipc.v1.StatusCode
import io.github.nervusos.ipc.v1.Success
import kotlin.test.*
import com.nervus.sdk.annotations.Method as MethodAnnotation

class ServiceStubTest {

    // --- resolveArguments tests ---

    @Test
    fun `resolveArguments single ByteArray returns raw payload`() {
        val stub = ServiceStub()
        val payload = byteArrayOf(1, 2, 3)
        val params = arrayOf<Class<*>>(ByteArray::class.java)
        val result = stub.resolveArguments(payload, params)
        assertEquals(1, result.size)
        assertContentEquals(payload, result[0] as ByteArray)
    }

    @Test
    fun `resolveArguments single protobuf Message parses payload`() {
        val stub = ServiceStub()
        val original = Success.newBuilder().setCode(StatusCode.STATUS_CODE_OK).build()
        val payload = original.toByteArray()
        val params = arrayOf<Class<*>>(Success::class.java)
        val result = stub.resolveArguments(payload, params)
        assertEquals(1, result.size)
        assertTrue(result[0] is Success)
        assertEquals(StatusCode.STATUS_CODE_OK, (result[0] as Success).code)
    }

    @Test
    fun `resolveArguments empty params returns empty`() {
        val stub = ServiceStub()
        val result = stub.resolveArguments(ByteArray(0), emptyArray())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `resolveArguments multiple params with TLV format`() {
        val stub = ServiceStub()
        // Build TLV payload: [len(4)=4][int_bytes(4)] [len(4)=5][str_bytes(5)]
        val intVal = 42
        val strVal = "hello"
        val intBytes = byteArrayOf(
            (intVal shr 24).toByte(),
            (intVal shr 16).toByte(),
            (intVal shr 8).toByte(),
            intVal.toByte()
        )
        val strBytes = strVal.toByteArray()
        val payload = intToBytes(4) + intBytes + intToBytes(5) + strBytes
        val params = arrayOf<Class<*>>(Int::class.java, String::class.java)
        val result = stub.resolveArguments(payload, params)
        assertEquals(2, result.size)
        assertEquals(42, result[0])
        assertEquals("hello", result[1])
    }

    @Test
    fun `resolveArguments multi with protobuf and Int`() {
        val stub = ServiceStub()
        val msg = Success.newBuilder().setCode(StatusCode.STATUS_CODE_ACCEPTED).build()
        val msgBytes = msg.toByteArray()
        val intVal = 99
        val intBytes = byteArrayOf(
            (intVal shr 24).toByte(),
            (intVal shr 16).toByte(),
            (intVal shr 8).toByte(),
            intVal.toByte()
        )
        val payload = intToBytes(msgBytes.size) + msgBytes + intToBytes(4) + intBytes
        val params = arrayOf<Class<*>>(Success::class.java, Int::class.java)
        val result = stub.resolveArguments(payload, params)
        assertEquals(2, result.size)
        assertTrue(result[0] is Success)
        assertEquals(StatusCode.STATUS_CODE_ACCEPTED, (result[0] as Success).code)
        assertEquals(99, result[1])
    }

    @Test
    fun `resolveArguments empty payload for multi-params returns nulls`() {
        val stub = ServiceStub()
        // Too few bytes for even the first length prefix
        val payload = byteArrayOf(1, 2, 3) // only 3 bytes, need 4 for length prefix
        val params = arrayOf<Class<*>>(Int::class.java)
        val result = stub.resolveArguments(payload, params)
        assertEquals(1, result.size)
        assertNull(result[0])
    }

    // --- serializeResult tests ---

    /** Takes the bytes of the Payload branch; landing in the Pending branch contradicts the test, so fail. */
    private fun bytesOf(result: InvokeResult): ByteArray = when (result) {
        is InvokeResult.Payload -> result.bytes
        InvokeResult.Pending -> fail("expected Payload, got Pending")
    }

    @Test
    fun `serializeResult ByteArray returns as-is`() {
        val stub = ServiceStub()
        val data = byteArrayOf(10, 20, 30)
        assertContentEquals(data, bytesOf(stub.serializeResult(data)))
    }

    @Test
    fun `serializeResult protobuf Message returns toByteArray`() {
        val stub = ServiceStub()
        val msg = Success.newBuilder().setCode(StatusCode.STATUS_CODE_OK).build()
        assertContentEquals(msg.toByteArray(), bytesOf(stub.serializeResult(msg)))
    }

    @Test
    fun `serializeResult String returns UTF-8 bytes`() {
        val stub = ServiceStub()
        assertContentEquals("hello".toByteArray(), bytesOf(stub.serializeResult("hello")))
    }

    @Test
    fun `serializeResult null returns empty`() {
        val stub = ServiceStub()
        assertContentEquals(ByteArray(0), bytesOf(stub.serializeResult(null)))
    }

    @Test
    fun `serializeResult Int returns toString bytes`() {
        val stub = ServiceStub()
        assertContentEquals("42".toByteArray(), bytesOf(stub.serializeResult(42)))
    }

    /**
     * OperationPending must take the Pending branch and must not be encoded as a payload.
     *
     * Without this pin it would fall into serializeResult's else branch and be sent to the caller as an object
     * address via toString, which on the wire is a valid OK response, so nobody would report an error.
     */
    @Test
    fun `serializeResult OperationPending maps to Pending`() {
        val stub = ServiceStub()
        assertEquals(InvokeResult.Pending, stub.serializeResult(OperationPending.INSTANCE))
    }

    // --- registerInterface / dispatch tests ---

    class TestService {
        @MethodAnnotation(id = 1)
        fun echo(data: ByteArray): ByteArray = data

        @MethodAnnotation(id = 2)
        fun greet(name: String): String = "Hello, $name!"

        @MethodAnnotation(id = 3)
        fun add(a: Int, b: Int): Int = a + b

        @MethodAnnotation(id = 4)
        suspend fun greetSuspend(name: String): String = "Hello, $name!"
    }

    @Test
    fun `dispatch ByteArray method`() {
        val stub = ServiceStub()
        val handler = DispatchHandler()
        val instance = TestService()
        stub.registerInterface(instance, handler)
        val payload = byteArrayOf(1, 2, 3, 4)
        val result = stub.dispatch(1, payload, CallerContext.getDefaultInstance())
        assertNotNull(result)
        assertTrue(result.hasSuccess())
        assertContentEquals(payload, result.success.payload.toByteArray())
    }

    @Test
    fun `dispatch String method`() {
        val stub = ServiceStub()
        val handler = DispatchHandler()
        val instance = TestService()
        stub.registerInterface(instance, handler)
        // InterfaceProxy serializes single String as TLV: [len(4)][data]
        val strBytes = "world".toByteArray()
        val payload = intToBytes(strBytes.size) + strBytes
        val result = stub.dispatch(2, payload, CallerContext.getDefaultInstance())
        assertNotNull(result)
        assertTrue(result.hasSuccess())
        val responseStr = result.success.payload.toStringUtf8()
        assertEquals("Hello, world!", responseStr)
    }

    @Test
    fun `dispatch suspend String method returns serialized result`() {
        val stub = ServiceStub()
        val handler = DispatchHandler()
        stub.registerInterface(TestService(), handler)
        val strBytes = "world".toByteArray()
        val payload = intToBytes(strBytes.size) + strBytes
        val result = stub.dispatch(4, payload, CallerContext.getDefaultInstance())
        assertNotNull(result)
        assertTrue(result.hasSuccess(), "failure=${result?.failure?.publicMessage}")
        assertEquals("Hello, world!", result.success.payload.toStringUtf8())
    }

    interface AnnotatedGreeter {
        @MethodAnnotation(id = 10)
        suspend fun greet(name: String): String
    }

    @Test
    fun `registerInterface finds @Method on interface when impl has none`() {
        val stub = ServiceStub()
        val handler = DispatchHandler()
        val impl = object : AnnotatedGreeter {
            override suspend fun greet(name: String): String = "Hello, $name!"
        }
        stub.registerInterface(impl, handler)
        val strBytes = "World".toByteArray()
        val payload = intToBytes(strBytes.size) + strBytes
        val result = stub.dispatch(10, payload, CallerContext.getDefaultInstance())
        assertNotNull(result)
        assertTrue(result.hasSuccess(), "failure=${result?.failure?.publicMessage}")
        assertEquals("Hello, World!", result.success.payload.toStringUtf8())
    }

    @Test
    fun `dispatch unknown methodId returns null`() {
        val stub = ServiceStub()
        val handler = DispatchHandler()
        stub.registerInterface(TestService(), handler)
        val result = stub.dispatch(99, ByteArray(0), CallerContext.getDefaultInstance())
        assertNull(result, "dispatch with unknown methodId should return null")
    }

    @Test
    fun `registerInterface duplicate methodId throws`() {
        val stub = ServiceStub()
        val handler = DispatchHandler()
        val instance = object {
            @MethodAnnotation(id = 1)
            fun foo() {}

            @MethodAnnotation(id = 1)
            fun bar() {}
        }
        assertFailsWith<IllegalArgumentException> {
            stub.registerInterface(instance, handler)
        }
    }

    @Test
    fun `dispatch method that throws returns failure result`() {
        val stub = ServiceStub()
        val handler = DispatchHandler()
        val instance = object {
            @MethodAnnotation(id = 1)
            fun failing(): ByteArray = throw RuntimeException("oops")
        }
        stub.registerInterface(instance, handler)
        val result = stub.dispatch(1, ByteArray(0), CallerContext.getDefaultInstance())
        assertNotNull(result)
        assertTrue(result.hasFailure())
        assertEquals(StatusCode.STATUS_CODE_INTERNAL.number, result.failure.codeValue)
    }

    @Test
    fun `registerInterface with dispatchCallback routes correctly`() {
        val stub = ServiceStub()
        val handler = DispatchHandler()
        val instance = TestService()
        var callbackInvoked = false
        stub.registerInterface(instance, handler) { methodId, payload, caller ->
            callbackInvoked = true
            assertEquals(1, methodId)
            DispatchResult.newBuilder()
                .setSuccess(Success.newBuilder()
                    .setCode(StatusCode.STATUS_CODE_OK)
                    .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
                    .build())
                .build()
        }
        val payload = byteArrayOf(10, 20)
        val dispatch = io.github.nervusos.ipc.v1.Dispatch.newBuilder()
            .setRouteId(100L)
            .setEndpointId(1L)
            .setMethodId(1)
            .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
            .build()
        val result = handler.dispatch(dispatch)
        assertTrue(callbackInvoked)
        assertEquals(100L, result.routeId)
        assertTrue(result.hasSuccess())
        assertContentEquals(payload, result.success.payload.toByteArray())
    }

    @Test
    fun `unregisterAll clears handlers`() {
        val stub = ServiceStub()
        val handler = DispatchHandler()
        stub.registerInterface(TestService(), handler)
        assertNotNull(stub.dispatch(1, ByteArray(0), CallerContext.getDefaultInstance()))
        stub.unregisterAll(handler)
        assertNull(stub.dispatch(1, ByteArray(0), CallerContext.getDefaultInstance()))
    }

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value shr 24).toByte(),
        (value shr 16).toByte(),
        (value shr 8).toByte(),
        value.toByte()
    )
}