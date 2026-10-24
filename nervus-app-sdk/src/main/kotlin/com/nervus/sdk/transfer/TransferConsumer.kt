package com.nervus.sdk.transfer

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure
import io.github.nervusos.ipc.v1.AttachTransfer
import io.github.nervusos.ipc.v1.AttachTransferResult
import io.github.nervusos.ipc.v1.TransferHandle
import io.github.nervusos.ipc.v1.TransferMode
import io.github.nervusos.ipc.v1.TransferRingConfig
import io.github.nervusos.ipc.v1.TransferRole
import java.io.IOException
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One frame read from a Transfer. [payload] is a private copy, safe to keep.
 *
 * @property sequence the frame number the producer gave it (0, 1, 2, ...); a gap means frames were
 *   skipped, either because this consumer only takes the latest or because it was lapped.
 * @property timestampNanos the producer's monotonic timestamp (for a camera: when it was captured).
 */
class TransferFrame(val payload: ByteArray, val sequence: Long, val flags: Int, val timestampNanos: Long)

/** The data plane refused or broke the protocol. */
class TransferException(message: String) : IOException(message)

/**
 * The consumer end of a Transfer: the high-speed data plane a Provider opens for a method such as
 * Camera@1 OpenStream (see transfer.proto and nervus-ipc's README, "Generic Transfer").
 *
 * The App gets a single-use [TransferHandle] in the method's response, attaches it here, and from
 * then on reads frames without nervud on the data path:
 *
 * - SHARED_MEMORY_RING: nervud passes a memfd and an eventfd over SCM_RIGHTS; both ends map the
 *   same ring. [readLatest] takes the newest finished frame and moves the cursor past everything
 *   older, which is what a live video consumer wants: a backlog of stale frames is worse than a gap.
 * - FRAMED_RELAY: length-prefixed NVT1 frames over the socket.
 *
 * Java has no SCM_RIGHTS, so the socket, fd passing and mmap go through libc with JNA. Linux only,
 * like nervud.
 *
 * Everything in shared memory is untrusted: the ring geometry comes from the control plane (through
 * nervud), the header copy is only checked once, and a slot claiming more bytes than a slot holds is
 * a protocol error, never clamped.
 */
class TransferConsumer private constructor(
    private val fd: Int,
    val mode: TransferMode,
    val maxPacketBytes: Int,
    private val ring: Ring?,
    private val memFd: Int,
    private val eventFd: Int,
) : AutoCloseable {

    @Volatile
    private var closed = false

    /**
     * Waits up to [timeoutMs] for a frame and returns the newest one, or null on timeout.
     * Throws [TransferException] if the producer is gone or broke the protocol.
     */
    fun readLatest(timeoutMs: Int): TransferFrame? {
        check(!closed) { "transfer closed" }
        return if (ring != null) readRing(timeoutMs) else readFramed(timeoutMs)
    }

    private fun readRing(timeoutMs: Int): TransferFrame? {
        val r = ring!!
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            r.takeLatest()?.let { return it }
            val left = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
            if (left <= 0) return null
            // The producer pokes the eventfd after each frame. Poll it (bounded, so a missed
            // wake-up costs at most a few milliseconds), then drain it.
            val pfd = PollFd()
            pfd.fd = eventFd
            pfd.events = POLLIN
            val n = libc.poll(pfd, 1, minOf(left, 20))
            if (n > 0) {
                if (pfd.revents.toInt() and (POLLERR or POLLHUP or POLLNVAL) != 0) {
                    throw TransferException("ring event descriptor closed")
                }
                val drain = ByteArray(8)
                libc.read(eventFd, drain, NativeLong(8))
            }
            // The control socket hangs up when the producer goes away.
            if (peerGone()) throw TransferException("producer disconnected")
        }
    }

    private fun peerGone(): Boolean {
        val pfd = PollFd()
        pfd.fd = fd
        pfd.events = POLLIN
        if (libc.poll(pfd, 1, 0) <= 0) return false
        return pfd.revents.toInt() and (POLLHUP or POLLERR) != 0
    }

    private fun readFramed(timeoutMs: Int): TransferFrame? {
        val pfd = PollFd()
        pfd.fd = fd
        pfd.events = POLLIN
        if (libc.poll(pfd, 1, timeoutMs) <= 0) return null
        val header = readExactly(fd, FRAME_HEADER_BYTES)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        if (header[0] != 'N'.code.toByte() || header[1] != 'V'.code.toByte() ||
            header[2] != 'T'.code.toByte() || header[3] != '1'.code.toByte()
        ) throw TransferException("invalid transfer frame magic")
        val flags = bb.getInt(4)
        if (flags != 0) throw TransferException("unknown transfer frame flags $flags")
        val seq = bb.getLong(8)
        val ts = bb.getLong(16)
        val len = bb.getInt(24)
        if (len < 0 || len > maxPacketBytes) throw TransferException("frame of $len bytes exceeds $maxPacketBytes")
        return TransferFrame(readExactly(fd, len), seq, flags, ts)
    }

    override fun close() {
        if (closed) return
        closed = true
        ring?.unmap()
        if (memFd >= 0) libc.close(memFd)
        if (eventFd >= 0) libc.close(eventFd)
        libc.close(fd)
    }

    /** The consumer's view of a mapped ring. Offsets and the seqlock follow transfer.proto (NVR1). */
    private class Ring(private val base: Pointer, private val len: Long, private val g: TransferRingConfig) {
        private val buf: ByteBuffer = base.getByteBuffer(0, len).order(ByteOrder.LITTLE_ENDIAN)
        private val slotMask = g.slotCount.toLong() - 1

        init {
            val magic = ByteArray(4).also { for (i in 0..3) it[i] = buf.get(i) }
            if (String(magic, Charsets.US_ASCII) != "NVR1") throw TransferException("bad ring magic")
            if (buf.getInt(4) != 1) throw TransferException("ring version ${buf.getInt(4)}, want 1")
            if (buf.getInt(8) != g.slotCount || buf.getInt(12) != g.slotSize || buf.getInt(16) != g.descriptorBytes) {
                throw TransferException("ring header disagrees with the control plane geometry")
            }
        }

        private fun loadAcquire(off: Int): Long = LONGS.getAcquire(buf, off) as Long
        private fun storeRelease(off: Int, v: Long) = LONGS.setRelease(buf, off, v)

        fun takeLatest(): TransferFrame? {
            val consumed = loadAcquire(OFF_CONSUMER)
            val produced = loadAcquire(OFF_PRODUCER)
            if (produced == consumed) return null
            if (produced < consumed) {
                // Impossible from an honest producer; jump forward rather than read garbage.
                storeRelease(OFF_CONSUMER, produced)
                return null
            }
            val n = produced - 1
            val slot = (n and slotMask).toInt()
            val desc = g.headerBytes + slot * g.descriptorBytes
            val seq = loadAcquire(desc + DESC_SEQUENCE)
            // Ready value of frame n is 2n+2; odd means being written, anything else was overwritten.
            if (seq != 2 * n + 2) return null
            val length = buf.getInt(desc + DESC_LENGTH)
            if (length < 0 || length > g.slotSize) {
                throw TransferException("slot $slot declares $length bytes > slot size ${g.slotSize}")
            }
            val flags = buf.getInt(desc + DESC_FLAGS)
            val ts = buf.getLong(desc + DESC_TIMESTAMP)
            val payloadOff = g.headerBytes.toLong() + g.slotCount.toLong() * g.descriptorBytes + slot.toLong() * g.slotSize
            val payload = ByteArray(length)
            base.read(payloadOff, payload, 0, length)
            // Re-check after copying: if the producer lapped us mid-copy the bytes are torn.
            if (loadAcquire(desc + DESC_SEQUENCE) != seq) return null
            storeRelease(OFF_CONSUMER, produced)
            return TransferFrame(payload, n, flags, ts)
        }

        fun unmap() {
            libc.munmap(base, NativeLong(len))
        }
    }

    companion object {
        private const val AF_UNIX = 1
        private const val SOCK_STREAM = 1
        private const val SOCK_CLOEXEC = 0x80000
        private const val SOL_SOCKET = 1
        private const val SCM_RIGHTS = 1
        private const val PROT_READ = 1
        private const val PROT_WRITE = 2
        private const val MAP_SHARED = 1
        private const val POLLIN: Short = 1
        private const val POLLERR = 8
        private const val POLLHUP = 16
        private const val POLLNVAL = 32
        private const val FRAME_HEADER_BYTES = 28
        private const val MAX_CONTROL_FRAME = 128 * 1024
        private const val HEADER_BYTES = 4096
        private const val DESCRIPTOR_BYTES = 32
        private const val OFF_PRODUCER = 24
        private const val OFF_CONSUMER = 32
        private const val DESC_SEQUENCE = 0
        private const val DESC_LENGTH = 8
        private const val DESC_FLAGS = 12
        private const val DESC_TIMESTAMP = 16

        private val LONGS: VarHandle =
            MethodHandles.byteBufferViewVarHandle(LongArray::class.java, ByteOrder.LITTLE_ENDIAN)

        private val libc: LibC by lazy { Native.load("c", LibC::class.java) }

        /**
         * Attaches the consumer end of [handle]. The handle is single-use and short-lived: attach it
         * as soon as the method returns, and never log or forward it (it is the credential).
         */
        fun attach(handle: TransferHandle, timeoutMs: Int = 3_000): TransferConsumer {
            require(handle.role == TransferRole.TRANSFER_ROLE_CONSUMER || handle.role == TransferRole.TRANSFER_ROLE_PEER) {
                "not a consumer handle: ${handle.role}"
            }
            require(handle.transferId.size() == 16) { "transfer id has ${handle.transferId.size()} bytes" }
            require(handle.attachTicket.size() >= 32) { "attach ticket too short" }
            require(handle.dataPlaneEndpoint.isNotEmpty()) { "no data plane endpoint" }

            val fd = libc.socket(AF_UNIX, SOCK_STREAM or SOCK_CLOEXEC, 0)
            if (fd < 0) throw TransferException("socket() failed: errno ${Native.getLastError()}")
            var ok = false
            try {
                connectUnix(fd, handle.dataPlaneEndpoint)
                setTimeout(fd, timeoutMs)
                val attach = AttachTransfer.newBuilder()
                    .setTransferId(handle.transferId)
                    .setAttachTicket(handle.attachTicket)
                    .setRole(handle.role)
                    .build()
                    .toByteArray()
                writeFrame(fd, attach)
                val len = ByteBuffer.wrap(readExactly(fd, 4)).order(ByteOrder.BIG_ENDIAN).int
                if (len < 0 || len > MAX_CONTROL_FRAME) throw TransferException("control frame of $len bytes")
                val result = AttachTransferResult.parseFrom(readExactly(fd, len))
                if (result.hasFailure()) throw TransferException("attach refused: ${result.failure.code}")
                if (!result.hasSuccess()) throw TransferException("AttachTransferResult has no outcome")
                val s = result.success
                if (s.mode != handle.mode) throw TransferException("transfer mode changed from ${handle.mode} to ${s.mode}")
                if (s.maxPacketBytes <= 0 || s.maxBytesPerSecond <= 0) throw TransferException("transfer limits must be non-zero")

                val consumer = when (s.mode) {
                    TransferMode.TRANSFER_MODE_SHARED_MEMORY_RING -> {
                        val g = s.ring
                        validateRing(g)
                        // The fds arrive with one data byte right after the result frame. Receiving
                        // them is still part of the handshake, so the timeout still applies.
                        val (memFd, eventFd) = receiveTwoFds(fd)
                        val total = g.headerBytes.toLong() + g.slotCount.toLong() * g.descriptorBytes +
                            g.slotCount.toLong() * g.slotSize
                        val base = libc.mmap(null, NativeLong(total), PROT_READ or PROT_WRITE, MAP_SHARED, memFd, NativeLong(0))
                        if (base == null || Pointer.nativeValue(base) == -1L) {
                            libc.close(memFd); libc.close(eventFd)
                            throw TransferException("mmap of the ring failed: errno ${Native.getLastError()}")
                        }
                        val ring = try {
                            Ring(base, total, g)
                        } catch (e: Exception) {
                            libc.munmap(base, NativeLong(total)); libc.close(memFd); libc.close(eventFd)
                            throw e
                        }
                        TransferConsumer(fd, s.mode, s.maxPacketBytes, ring, memFd, eventFd)
                    }
                    TransferMode.TRANSFER_MODE_FRAMED_RELAY ->
                        TransferConsumer(fd, s.mode, s.maxPacketBytes, null, -1, -1)
                    else -> throw TransferException("transfer mode ${s.mode} is not supported")
                }
                setTimeout(fd, 0)
                ok = true
                return consumer
            } finally {
                if (!ok) libc.close(fd)
            }
        }

        private fun validateRing(g: TransferRingConfig) {
            if (g.slotCount <= 0 || g.slotCount and (g.slotCount - 1) != 0) throw TransferException("slot_count ${g.slotCount} is not a power of two")
            if (g.slotSize <= 0) throw TransferException("slot_size is zero")
            if (g.headerBytes != HEADER_BYTES) throw TransferException("header_bytes ${g.headerBytes}, want $HEADER_BYTES")
            if (g.descriptorBytes != DESCRIPTOR_BYTES) throw TransferException("descriptor_bytes ${g.descriptorBytes}, want $DESCRIPTOR_BYTES")
            val total = g.headerBytes.toLong() + g.slotCount.toLong() * (g.descriptorBytes + g.slotSize.toLong())
            if (total > (1L shl 34)) throw TransferException("ring size overflow")
        }

        private fun connectUnix(fd: Int, path: String) {
            val p = path.toByteArray(Charsets.UTF_8)
            if (p.size >= 108) throw TransferException("socket path too long")
            val addr = ByteArray(110)
            addr[0] = AF_UNIX.toByte() // sa_family, little-endian short
            System.arraycopy(p, 0, addr, 2, p.size)
            if (libc.connect(fd, addr, 2 + p.size + 1) != 0) {
                throw TransferException("connect $path failed: errno ${Native.getLastError()}")
            }
        }

        private fun setTimeout(fd: Int, ms: Int) {
            // struct timeval { long tv_sec; long tv_usec; }
            val tv = Memory(16)
            tv.setLong(0, (ms / 1000).toLong())
            tv.setLong(8, ((ms % 1000) * 1000).toLong())
            libc.setsockopt(fd, SOL_SOCKET, 20 /* SO_RCVTIMEO */, tv, 16)
            libc.setsockopt(fd, SOL_SOCKET, 21 /* SO_SNDTIMEO */, tv, 16)
        }

        private fun writeFrame(fd: Int, body: ByteArray) {
            val out = ByteBuffer.allocate(4 + body.size).order(ByteOrder.BIG_ENDIAN)
            out.putInt(body.size).put(body)
            val bytes = out.array()
            var off = 0
            while (off < bytes.size) {
                val chunk = bytes.copyOfRange(off, bytes.size)
                val n = libc.write(fd, chunk, NativeLong(chunk.size.toLong())).toInt()
                if (n <= 0) throw TransferException("write failed: errno ${Native.getLastError()}")
                off += n
            }
        }

        internal fun readExactly(fd: Int, n: Int): ByteArray {
            val out = ByteArray(n)
            var off = 0
            while (off < n) {
                val chunk = ByteArray(n - off)
                val r = libc.read(fd, chunk, NativeLong(chunk.size.toLong())).toInt()
                if (r == 0) throw TransferException("peer closed the data plane")
                if (r < 0) throw TransferException("read failed: errno ${Native.getLastError()}")
                System.arraycopy(chunk, 0, out, off, r)
                off += r
            }
            return out
        }

        /** recvmsg one byte plus SCM_RIGHTS; the order is fixed as memfd, eventfd. */
        private fun receiveTwoFds(fd: Int): Pair<Int, Int> {
            val data = Memory(1)
            val iov = Iovec()
            iov.iov_base = data
            iov.iov_len = NativeLong(1)
            iov.write()
            val control = Memory(64)
            control.clear()
            val msg = Msghdr()
            msg.msg_iov = iov.pointer
            msg.msg_iovlen = NativeLong(1)
            msg.msg_control = control
            msg.msg_controllen = NativeLong(64)
            msg.write()
            val n = libc.recvmsg(fd, msg, 0x40000000 /* MSG_CMSG_CLOEXEC */).toLong()
            if (n <= 0) throw TransferException("receive ring descriptors failed: errno ${Native.getLastError()}")
            msg.read()
            val fds = mutableListOf<Int>()
            var off = 0L
            val clen = msg.msg_controllen.toLong()
            while (off + 16 <= clen) {
                val cmsgLen = control.getLong(off)
                val level = control.getInt(off + 8)
                val type = control.getInt(off + 12)
                if (cmsgLen < 16) break
                if (level == SOL_SOCKET && type == SCM_RIGHTS) {
                    val count = ((cmsgLen - 16) / 4).toInt()
                    for (i in 0 until count) fds += control.getInt(off + 16 + 4L * i)
                }
                off += (cmsgLen + 7) and 7L.inv()
            }
            if (fds.size != 2) {
                fds.forEach { libc.close(it) }
                throw TransferException("expected exactly 2 ring descriptors, got ${fds.size}")
            }
            return fds[0] to fds[1]
        }
    }

    @Suppress("FunctionName")
    private interface LibC : Library {
        fun socket(domain: Int, type: Int, protocol: Int): Int
        fun connect(fd: Int, addr: ByteArray, len: Int): Int
        fun setsockopt(fd: Int, level: Int, name: Int, value: Pointer, len: Int): Int
        fun read(fd: Int, buf: ByteArray, n: NativeLong): NativeLong
        fun write(fd: Int, buf: ByteArray, n: NativeLong): NativeLong
        fun recvmsg(fd: Int, msg: Msghdr, flags: Int): NativeLong
        fun poll(fds: PollFd, nfds: Int, timeout: Int): Int
        fun mmap(addr: Pointer?, len: NativeLong, prot: Int, flags: Int, fd: Int, off: NativeLong): Pointer?
        fun munmap(addr: Pointer, len: NativeLong): Int
        fun close(fd: Int): Int
    }

    @Structure.FieldOrder("iov_base", "iov_len")
    class Iovec : Structure() {
        @JvmField var iov_base: Pointer? = null
        @JvmField var iov_len: NativeLong = NativeLong(0)
    }

    @Structure.FieldOrder("msg_name", "msg_namelen", "msg_iov", "msg_iovlen", "msg_control", "msg_controllen", "msg_flags")
    class Msghdr : Structure() {
        @JvmField var msg_name: Pointer? = null
        @JvmField var msg_namelen: Int = 0
        @JvmField var msg_iov: Pointer? = null
        @JvmField var msg_iovlen: NativeLong = NativeLong(0)
        @JvmField var msg_control: Pointer? = null
        @JvmField var msg_controllen: NativeLong = NativeLong(0)
        @JvmField var msg_flags: Int = 0
    }

    @Structure.FieldOrder("fd", "events", "revents")
    class PollFd : Structure() {
        @JvmField var fd: Int = -1
        @JvmField var events: Short = 0
        @JvmField var revents: Short = 0
    }
}
