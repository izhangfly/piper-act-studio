package com.nervus.sdk.ipc.connection

import com.google.protobuf.InvalidProtocolBufferException
import io.github.nervusos.ipc.v1.Envelope
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer

internal class ProtocolViolationException(message: String) : Exception(message)

internal class FrameReader(private val input: InputStream) {
    private val headerBuf = ByteBuffer.allocate(HEADER_SIZE)

    fun readFrame(timeoutMs: Long = 0): Envelope {
        headerBuf.clear()
        readFully(headerBuf, timeoutMs)
        headerBuf.flip()
        val length = headerBuf.getInt().toLong() and 0xFFFFFFFFL

        if (length == 0L) {
            throw ProtocolViolationException("zero-length frame")
        }
        if (length > MAX_FRAME_BYTES) {
            throw ProtocolViolationException(
                "frame too large: $length > $MAX_FRAME_BYTES"
            )
        }

        val bodyBuf = ByteBuffer.allocate(length.toInt())
        readFully(bodyBuf, timeoutMs)
        return try {
            Envelope.parseFrom(bodyBuf.array())
        } catch (e: InvalidProtocolBufferException) {
            throw ProtocolViolationException("malformed envelope: ${e.message}")
        }
    }

    private fun readFully(buf: ByteBuffer, timeoutMs: Long = 0) {
        val deadline = if (timeoutMs > 0) System.nanoTime() + timeoutMs * 1_000_000L else 0L
        while (buf.hasRemaining()) {
            if (deadline > 0 && System.nanoTime() >= deadline) {
                throw java.net.SocketTimeoutException("read timed out after ${timeoutMs}ms")
            }
            val n = input.read(buf.array(), buf.position(), buf.remaining())
            if (n == -1) {
                throw EOFException("connection closed unexpectedly")
            }
            buf.position(buf.position() + n)
        }
    }

    companion object {
        const val HEADER_SIZE = 4
        const val MAX_FRAME_BYTES = 128 * 1024
    }
}
