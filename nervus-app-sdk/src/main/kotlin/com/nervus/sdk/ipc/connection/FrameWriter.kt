package com.nervus.sdk.ipc.connection

import io.github.nervusos.ipc.v1.Envelope
import java.io.OutputStream
import java.nio.ByteBuffer

internal class FrameWriter(private val output: OutputStream) {
    companion object {
        const val HEADER_SIZE = 4
        const val MAX_FRAME_BYTES = 128 * 1024
    }
    private val lock = Any()

    fun writeFrame(envelope: Envelope) {
        val body = envelope.toByteArray()
        val length = body.size

        if (length > MAX_FRAME_BYTES) {
            throw ProtocolViolationException(
                "frame too large to write: $length > $MAX_FRAME_BYTES"
            )
        }

        val header = ByteBuffer.allocate(HEADER_SIZE)
        header.putInt(length)
        header.flip()

        synchronized(lock) {
            output.write(header.array())
            output.write(body)
            output.flush()
        }
    }
}
