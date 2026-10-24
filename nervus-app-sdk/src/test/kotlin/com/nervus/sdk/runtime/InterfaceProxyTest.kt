package com.nervus.sdk.runtime

import com.nervus.sdk.annotations.Interface as InterfaceAnnotation
import com.nervus.sdk.annotations.Method as MethodAnnotation
import com.nervus.sdk.ipc.NervusClient
import io.github.nervusos.ipc.v1.*
import kotlin.test.Test
import kotlin.test.*
import java.util.concurrent.CompletableFuture

class InterfaceProxyTest {

    @InterfaceAnnotation(id = "test.interface")
    interface TestInterface {
        @MethodAnnotation(id = 1)
        fun echo(data: ByteArray): CompletableFuture<ByteArray>

        @MethodAnnotation(id = 2)
        fun getValue(): CompletableFuture<ByteArray>

        @MethodAnnotation(id = 3)
        fun rawCall(data: ByteArray): CompletableFuture<*>
    }

    @Test
    fun `serializeArgs single ByteArray returns raw bytes`() {
        val client = createFakeClient()
        val endpoint = ResolvedEndpoint(endpointId = 1, interfaceId = "test", interfaceMajor = 1, interfaceMinor = 0)
        val proxy = InterfaceProxy(client, endpoint)
        val bytes = byteArrayOf(1, 2, 3, 4)
        val result = proxy.serializeArgs(arrayOf(bytes))
        assertContentEquals(bytes, result, "single ByteArray arg should return raw bytes")
    }

    @Test
    fun `serializeArgs single protobuf Message returns raw bytes`() {
        val client = createFakeClient()
        val endpoint = ResolvedEndpoint(endpointId = 1, interfaceId = "test", interfaceMajor = 1, interfaceMinor = 0)
        val proxy = InterfaceProxy(client, endpoint)
        val msg = Success.newBuilder().setCode(StatusCode.STATUS_CODE_OK).build()
        val result = proxy.serializeArgs(arrayOf(msg))
        assertContentEquals(msg.toByteArray(), result, "single protobuf Message arg should return raw toByteArray()")
    }

    @Test
    fun `serializeArgs multiple args produces TLV format`() {
        val client = createFakeClient()
        val endpoint = ResolvedEndpoint(endpointId = 1, interfaceId = "test", interfaceMajor = 1, interfaceMinor = 0)
        val proxy = InterfaceProxy(client, endpoint)
        val intVal = 42
        val strVal = "hello"
        val result = proxy.serializeArgs(arrayOf(intVal, strVal))
        // Format: [len_prefix(4) + int_bytes(4)] [len_prefix(4) + str_bytes(5)]
        assertTrue(result.isNotEmpty())
        // First length prefix should be 4 (sizeof int)
        val firstLen = ((result[0].toInt() and 0xFF) shl 24) or
                ((result[1].toInt() and 0xFF) shl 16) or
                ((result[2].toInt() and 0xFF) shl 8) or
                (result[3].toInt() and 0xFF)
        assertEquals(4, firstLen, "first arg length prefix should be 4 bytes for Int")
        // Second length prefix
        val secondLen = ((result[8].toInt() and 0xFF) shl 24) or
                ((result[9].toInt() and 0xFF) shl 16) or
                ((result[10].toInt() and 0xFF) shl 8) or
                (result[11].toInt() and 0xFF)
        assertEquals(5, secondLen, "second arg length prefix should be 5 for 'hello'")
    }

    @Test
    fun `extractPayload from success response returns payload bytes`() {
        val client = createFakeClient()
        val endpoint = ResolvedEndpoint(endpointId = 1, interfaceId = "test", interfaceMajor = 1, interfaceMinor = 0)
        val proxy = InterfaceProxy(client, endpoint)
        val payload = byteArrayOf(10, 20, 30)
        val response = Response.newBuilder()
            .setRequestId(1L)
            .setSuccess(Success.newBuilder()
                .setCode(StatusCode.STATUS_CODE_OK)
                .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
                .build())
            .build()
        val result = proxy.extractPayload(response)
        assertContentEquals(payload, result)
    }

    @Test
    fun `extractPayload from failure response throws`() {
        val client = createFakeClient()
        val endpoint = ResolvedEndpoint(endpointId = 1, interfaceId = "test", interfaceMajor = 1, interfaceMinor = 0)
        val proxy = InterfaceProxy(client, endpoint)
        val response = Response.newBuilder()
            .setRequestId(1L)
            .setFailure(Failure.newBuilder()
                .setCode(StatusCode.STATUS_CODE_PERMISSION_DENIED)
                .setPublicMessage("access denied")
                .build())
            .build()
        val ex = assertFailsWith<RuntimeException> {
            proxy.extractPayload(response)
        }
        assertTrue(ex.message!!.contains("access denied"))
    }

    @Test
    fun `extractPayload from unexpected success code throws`() {
        val client = createFakeClient()
        val endpoint = ResolvedEndpoint(endpointId = 1, interfaceId = "test", interfaceMajor = 1, interfaceMinor = 0)
        val proxy = InterfaceProxy(client, endpoint)
        val response = Response.newBuilder()
            .setRequestId(1L)
            .setSuccess(Success.newBuilder()
                .setCode(StatusCode.STATUS_CODE_NOT_FOUND)
                .build())
            .build()
        val ex = assertFailsWith<RuntimeException> {
            proxy.extractPayload(response)
        }
        assertTrue(ex.message!!.contains("unexpected status"))
    }

    @Test
    fun `serializeArgs empty args returns empty array`() {
        val client = createFakeClient()
        val endpoint = ResolvedEndpoint(endpointId = 1, interfaceId = "test", interfaceMajor = 1, interfaceMinor = 0)
        val proxy = InterfaceProxy(client, endpoint)
        val result = proxy.serializeArgs(arrayOfNulls<Any>(0))
        assertContentEquals(ByteArray(0), result)
    }

    @Test
    fun `serializeArgs Long arg`() {
        val client = createFakeClient()
        val endpoint = ResolvedEndpoint(endpointId = 1, interfaceId = "test", interfaceMajor = 1, interfaceMinor = 0)
        val proxy = InterfaceProxy(client, endpoint)
        val longVal = 0x0102030405060708L
        val result = proxy.serializeArgs(arrayOf(longVal))
        // Length prefix (4) = 8, then 8 bytes of long
        val len = ((result[0].toInt() and 0xFF) shl 24) or
                ((result[1].toInt() and 0xFF) shl 16) or
                ((result[2].toInt() and 0xFF) shl 8) or
                (result[3].toInt() and 0xFF)
        assertEquals(8, len)
        assertEquals(0x01, result[4].toInt() and 0xFF)
        assertEquals(0x08, result[11].toInt() and 0xFF)
    }

    private fun createFakeClient(): NervusClient {
        return object : NervusClient(sdkName = "test", sdkVersion = "1.0") {
            // Fake client that doesn't connect
        }
    }
}