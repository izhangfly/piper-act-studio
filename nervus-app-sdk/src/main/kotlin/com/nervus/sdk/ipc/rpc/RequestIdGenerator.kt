package com.nervus.sdk.ipc.rpc

import java.util.concurrent.atomic.AtomicLong

internal class RequestIdGenerator {
    private val counter = AtomicLong(INITIAL_ID)

    fun next(): Long {
        while (true) {
            val current = counter.get()
            if (current == Long.MAX_VALUE) {
                throw IllegalStateException("request_id exhausted: reached Long.MAX_VALUE")
            }
            val next = current + 1
            if (counter.compareAndSet(current, next)) {
                return next
            }
        }
    }

    companion object {
        const val INITIAL_ID = 0L
    }
}
