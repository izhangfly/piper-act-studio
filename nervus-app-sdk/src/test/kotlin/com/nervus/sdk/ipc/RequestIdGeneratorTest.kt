package com.nervus.sdk.ipc

import com.nervus.sdk.ipc.rpc.RequestIdGenerator
import kotlin.test.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RequestIdGeneratorTest {

    @Test
    fun `starts at 1 and increments`() {
        val gen = RequestIdGenerator()
        assertEquals(1L, gen.next())
        assertEquals(2L, gen.next())
        assertEquals(3L, gen.next())
    }

    @Test
    fun `concurrent safety`() {
        val gen = RequestIdGenerator()
        val threadCount = 4
        val idsPerThread = 1000
        val executor = Executors.newFixedThreadPool(threadCount)
        val ids = ConcurrentHashMap.newKeySet<Long>()
        val latch = CountDownLatch(threadCount)

        repeat(threadCount) {
            executor.submit {
                try {
                    repeat(idsPerThread) {
                        assertTrue(ids.add(gen.next()))
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        assertEquals(threadCount * idsPerThread.toLong(), ids.size.toLong())
    }

    @Test
    fun `throws at Long MAX_VALUE`() {
        val gen = RequestIdGenerator()
        val field = gen.javaClass.getDeclaredField("counter")
        field.isAccessible = true
        val counter = field.get(gen) as java.util.concurrent.atomic.AtomicLong
        counter.set(Long.MAX_VALUE - 1)

        assertEquals(Long.MAX_VALUE, gen.next())
        assertFailsWith<IllegalStateException> {
            gen.next()
        }
    }
}
