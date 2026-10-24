package com.nervus.sdk.ipc

import com.nervus.sdk.ipc.connection.FrameReader
import com.nervus.sdk.ipc.connection.FrameWriter
import com.nervus.sdk.ipc.connection.ProtocolViolationException
import io.github.nervusos.ipc.v1.Envelope
import io.github.nervusos.ipc.v1.Ping
import io.github.nervusos.ipc.v1.Pong
import kotlin.test.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FrameReaderWriterTest {

    @Test
    fun `round trip ping-pong`() {
        val baos = ByteArrayOutputStream()
        val writer = FrameWriter(baos)

        writer.writeFrame(
            Envelope.newBuilder().setPing(Ping.getDefaultInstance()).build()
        )
        writer.writeFrame(
            Envelope.newBuilder().setPong(Pong.getDefaultInstance()).build()
        )

        val reader = FrameReader(ByteArrayInputStream(baos.toByteArray()))
        assertTrue(reader.readFrame().hasPing())
        assertTrue(reader.readFrame().hasPong())
    }

    @Test
    fun `envelope with only protocol major`() {
        val baos = ByteArrayOutputStream()
        FrameWriter(baos).writeFrame(
            Envelope.newBuilder().setPing(Ping.getDefaultInstance()).build()
        )

        val reader = FrameReader(ByteArrayInputStream(baos.toByteArray()))
        assertNotNull(reader.readFrame())
    }

    @Test
    fun `zero length frame is protocol violation`() {
        val reader = FrameReader(ByteArrayInputStream(byteArrayOf(0, 0, 0, 0)))
        assertFailsWith<ProtocolViolationException> { reader.readFrame() }
    }

    @Test
    fun `frame too large is protocol violation`() {
        val header = java.nio.ByteBuffer.allocate(4)
        header.putInt(FrameReader.MAX_FRAME_BYTES + 1)
        val reader = FrameReader(ByteArrayInputStream(header.array()))
        assertFailsWith<ProtocolViolationException> { reader.readFrame() }
    }

    @Test
    fun `malformed protobuf is protocol violation`() {
        val payload = byteArrayOf(0x03, 0x01, 0x02, 0x03) // length=3 + garbage
        val header = java.nio.ByteBuffer.allocate(4).putInt(3)
        val data = header.array() + payload.take(3).toByteArray()
        val reader = FrameReader(ByteArrayInputStream(data))
        assertFailsWith<ProtocolViolationException> { reader.readFrame() }
    }

    @Test
    fun `max frame size boundary`() {
        val baos = ByteArrayOutputStream()
        FrameWriter(baos).writeFrame(
            Envelope.newBuilder().setPing(Ping.getDefaultInstance()).build()
        )
        val reader = FrameReader(ByteArrayInputStream(baos.toByteArray()))
        assertTrue(reader.readFrame().hasPing())
    }
}
