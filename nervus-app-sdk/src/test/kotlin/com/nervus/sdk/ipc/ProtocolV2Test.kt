package com.nervus.sdk.ipc

import com.nervus.sdk.ipc.connection.FrameReader
import com.nervus.sdk.ipc.connection.FrameWriter
import com.nervus.sdk.ipc.connection.HelloHandshake
import com.nervus.sdk.ipc.connection.PROTOCOL_MAJOR_MAX
import com.nervus.sdk.ipc.connection.PROTOCOL_MAJOR_MIN
import com.nervus.sdk.ipc.connection.PROTOCOL_MINOR_MAX
import com.nervus.sdk.ipc.connection.ProtocolViolationException
import io.github.nervusos.ipc.v1.*
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.*

/**
 * Tests for the V2 wire protocol layer.
 *
 * Everything here [fails silently when wrong]: a mis-negotiated version still connects, a missing scope
 * still gets sent. The symptoms only show at run time, and look like something else.
 */
class ProtocolV2Test {

    // ---- version ---------------------------------------------------------------

    @Test
    fun `sdk speaks protocol 2 only`() {
        assertEquals(2, PROTOCOL_MAJOR_MIN)
        assertEquals(2, PROTOCOL_MAJOR_MAX)
        assertEquals(0, PROTOCOL_MINOR_MAX)
    }

    /**
     * [Why the lower bound is 2]: a v1 nervud must fail to connect, not connect and then quietly
     * diverge in meaning. With min = 1 it would handshake, then give a different device than v2
     * on a Resolve with an empty selector, with neither side reporting an error.
     */
    @Test
    fun `server choosing protocol 1 is rejected`() {
        val (client, server) = pipePair()

        // Kernel side: receive Hello, reply with a v1 HelloAck
        Thread {
            server.reader.readFrame()
            server.writer.writeFrame(
                Envelope.newBuilder().setHelloAck(
                    HelloAck.newBuilder().setSuccess(
                        HelloAckSuccess.newBuilder()
                            .setProtocolMajor(1)
                            .setProtocolMinor(0)
                            .setLimits(ConnectionLimits.getDefaultInstance())
                            .build()
                    ).build()
                ).build()
            )
        }.start()

        assertFailsWith<ProtocolViolationException> {
            HelloHandshake(client.reader, client.writer).negotiate(
                minMajor = PROTOCOL_MAJOR_MIN,
                maxMajor = PROTOCOL_MAJOR_MAX,
                maxMinor = PROTOCOL_MINOR_MAX,
                sdkName = "test",
                sdkVersion = "0",
            )
        }
    }

    @Test
    fun `hello advertises protocol 2`() {
        val (client, server) = pipePair()

        Thread {
            HelloHandshake(client.reader, client.writer).runCatching {
                negotiate(
                    minMajor = PROTOCOL_MAJOR_MIN,
                    maxMajor = PROTOCOL_MAJOR_MAX,
                    maxMinor = PROTOCOL_MINOR_MAX,
                    sdkName = "test",
                    sdkVersion = "0",
                )
            }
        }.start()

        val hello = server.reader.readFrame().hello
        assertEquals(2, hello.minProtocolMajor)
        assertEquals(2, hello.maxProtocolMajor)
    }

    // ---- instance scope --------------------------------------------------------

    /**
     * scope is on the Envelope, not in the payload: nervud has to understand it to decide ownership,
     * and can't decode bytes according to a Provider's schema. This test guards against it moving back into the payload.
     */
    @Test
    fun `subscribe carries scope on the envelope`() {
        val (client, server) = pipePair()

        client.writer.writeFrame(
            Envelope.newBuilder().setSubscribe(
                Subscribe.newBuilder()
                    .setRequestId(1)
                    .setEndpointId(5)
                    .setEventId(1)
                    .setScope(4242)
                    .build()
            ).build()
        )

        val sub = server.reader.readFrame().subscribe
        assertEquals(4242L, sub.scope)
        assertTrue(sub.payload.isEmpty, "scope must not be stuffed into the payload")
    }

    @Test
    fun `bind and release event scope round trip`() {
        val (client, server) = pipePair()

        client.writer.writeFrame(
            Envelope.newBuilder().setBindEventScope(
                BindEventScope.newBuilder()
                    .setEndpointId(7)
                    .setScope(99)
                    .setOriginRouteId(1234)
                    .build()
            ).build()
        )
        client.writer.writeFrame(
            Envelope.newBuilder().setBindEventScope(
                BindEventScope.newBuilder()
                    .setEndpointId(7)
                    .setScope(99)
                    .setReleased(true)
                    .build()
            ).build()
        )

        val bind = server.reader.readFrame().bindEventScope
        assertEquals(7L, bind.endpointId)
        assertEquals(99L, bind.scope)
        assertEquals(1234L, bind.originRouteId)
        assertFalse(bind.released)

        val release = server.reader.readFrame().bindEventScope
        assertTrue(release.released)
        assertEquals(99L, release.scope)
    }

    @Test
    fun `publish event round trip`() {
        val (client, server) = pipePair()

        client.writer.writeFrame(
            Envelope.newBuilder().setPublishEvent(
                PublishEvent.newBuilder()
                    .setEndpointId(7)
                    .setEventId(2)
                    .setPayload(com.google.protobuf.ByteString.copyFromUtf8("state"))
                    .setMonotonicTimestampNanos(555)
                    .build()
            ).build()
        )

        val ev = server.reader.readFrame().publishEvent
        assertEquals(7L, ev.endpointId)
        assertEquals(2, ev.eventId)
        assertEquals("state", ev.payload.toStringUtf8())
        assertEquals(555L, ev.monotonicTimestampNanos)
    }

    // ---- selector ---------------------------------------------------------

    @Test
    fun `no selector when nothing is specified`() {
        assertNull(
            buildResourceSelector(
                "", "", emptyMap(),
                ResourceSelectionPolicy.RESOURCE_SELECTION_POLICY_UNSPECIFIED
            )
        )
    }

    /**
     * [A labels-only selection must still be sent]. Deciding "is there a selector" from type/role alone
     * would drop the whole selector from a call that selects a device by meaning, and selecting by meaning is
     * what v2 recommends. The client gets it wrong, the server reports it.
     */
    @Test
    fun `labels alone produce a selector`() {
        val sel = buildResourceSelector(
            "", "", mapOf("nervus.camera.facing" to "front"),
            ResourceSelectionPolicy.RESOURCE_SELECTION_POLICY_UNSPECIFIED
        )
        assertNotNull(sel)
        assertEquals("front", sel.labelsMap["nervus.camera.facing"])
        assertEquals("", sel.type)
    }

    @Test
    fun `policy alone produces a selector`() {
        val sel = buildResourceSelector(
            "", "", emptyMap(),
            ResourceSelectionPolicy.RESOURCE_SELECTION_POLICY_SYSTEM_PREFERRED
        )
        assertNotNull(sel)
        assertEquals(
            ResourceSelectionPolicy.RESOURCE_SELECTION_POLICY_SYSTEM_PREFERRED,
            sel.policy
        )
    }

    @Test
    fun `all four fields are carried through`() {
        val sel = buildResourceSelector(
            "nervus.resource.camera",
            "front",
            mapOf("a" to "1", "b" to "2"),
            ResourceSelectionPolicy.RESOURCE_SELECTION_POLICY_REQUIRE_UNIQUE
        )
        assertNotNull(sel)
        assertEquals("nervus.resource.camera", sel.type)
        assertEquals("front", sel.role)
        assertEquals(2, sel.labelsCount)
        assertEquals(
            ResourceSelectionPolicy.RESOURCE_SELECTION_POLICY_REQUIRE_UNIQUE,
            sel.policy
        )
    }

    // ---- pipe pair -------------------------------------------------------------

    private class Side(val reader: FrameReader, val writer: FrameWriter)

    private fun pipePair(): Pair<Side, Side> {
        val aOut = PipedOutputStream()
        val aIn = PipedInputStream()
        val bOut = PipedOutputStream()
        val bIn = PipedInputStream()
        aOut.connect(bIn)
        bOut.connect(aIn)
        return Side(FrameReader(aIn), FrameWriter(aOut)) to
            Side(FrameReader(bIn), FrameWriter(bOut))
    }
}

/**
 * Rendering the detail of a Failure.
 *
 * nervud doesn't fill public_message; the detail is in error_detail. Without decoding it, app authors see
 * a bare `(code=STATUS_CODE_FAILED_PRECONDITION)`, and on the Resolve path that code
 * maps to four completely different fixes.
 */
class FailureTextTest {

    private fun failureWith(reason: ResolveEndpointReason): Failure =
        Failure.newBuilder()
            .setCode(StatusCode.STATUS_CODE_FAILED_PRECONDITION)
            .setErrorDetail(
                ResolveEndpointErrorDetail.newBuilder().setReason(reason).build().toByteString()
            )
            .build()

    /** [The core]: the detail must appear in the message; a code alone makes people try all four fixes. */
    @Test
    fun `reason is surfaced, not just the code`() {
        val text = describeFailure(
            failureWith(ResolveEndpointReason.RESOLVE_ENDPOINT_REASON_INTERFACE_NOT_FOUND)
        )
        assertContains(text, "INTERFACE_NOT_FOUND")
        assertContains(text, "STATUS_CODE_FAILED_PRECONDITION")
    }

    @Test
    fun `each resolve reason carries an actionable hint`() {
        val reasons = listOf(
            ResolveEndpointReason.RESOLVE_ENDPOINT_REASON_INTERFACE_NOT_FOUND,
            ResolveEndpointReason.RESOLVE_ENDPOINT_REASON_VERSION_MISMATCH,
            ResolveEndpointReason.RESOLVE_ENDPOINT_REASON_RESOURCE_NOT_FOUND,
            ResolveEndpointReason.RESOLVE_ENDPOINT_REASON_RESOURCE_AMBIGUOUS,
        )
        for (r in reasons) {
            val text = describeFailure(failureWith(r))
            assertTrue(text.contains("(hint:"), "reason $r gives no hint about what to do: $text")
        }
    }

    /** With no detail it doesn't crash, it falls back to the code. */
    @Test
    fun `bare failure degrades to the code`() {
        val text = describeFailure(
            Failure.newBuilder().setCode(StatusCode.STATUS_CODE_NOT_FOUND).build()
        )
        assertEquals("code=STATUS_CODE_NOT_FOUND", text)
    }

    /**
     * [An undecodable detail must not throw]. When a newer kernel changes the detail type, the client should fall back to
     * reporting the code instead of turning a usable error report into a parse exception, which would hide the real error.
     */
    @Test
    fun `undecodable detail degrades instead of throwing`() {
        val text = describeFailure(
            Failure.newBuilder()
                .setCode(StatusCode.STATUS_CODE_INTERNAL)
                .setErrorDetail(com.google.protobuf.ByteString.copyFrom(byteArrayOf(-1, -1, -1, -1)))
                .build()
        )
        assertContains(text, "STATUS_CODE_INTERNAL")
    }

    /** A non-empty public_message is included alongside, neither overwriting the other. */
    @Test
    fun `public message is kept alongside the reason`() {
        val text = describeFailure(
            Failure.newBuilder()
                .setCode(StatusCode.STATUS_CODE_FAILED_PRECONDITION)
                .setPublicMessage("provider not ready")
                .setErrorDetail(
                    ResolveEndpointErrorDetail.newBuilder()
                        .setReason(ResolveEndpointReason.RESOLVE_ENDPOINT_REASON_INTERFACE_NOT_FOUND)
                        .build().toByteString()
                )
                .build()
        )
        assertContains(text, "provider not ready")
        assertContains(text, "INTERFACE_NOT_FOUND")
    }
}
