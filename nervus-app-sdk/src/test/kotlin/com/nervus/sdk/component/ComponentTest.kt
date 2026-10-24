package com.nervus.sdk.component

import com.nervus.sdk.ipc.ConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.*

class ComponentTest {

    private open class TestComponent(
        overrideConfig: ComponentConfig = ComponentConfig(autoReconnect = false)
    ) : Component(overrideConfig) {
        var startCalled = false
        var closeCalled = false
        var doStartCalled = false
        var doCloseCalled = false
        var active = true

        override fun isActive(): Boolean = active
        override fun doStart() {
            doStartCalled = true
            startCalled = true
        }
        override fun doClose() {
            doCloseCalled = true
            closeCalled = true
        }

        var onReadyCalled = false
        var onStopCalled = false
        var onConnectCalled = false

        override fun onReady() { onReadyCalled = true }
        override fun onStop() { onStopCalled = true }
        override fun onConnect() { onConnectCalled = true }
    }

    @Test
    fun `start transitions from DISCONNECTED to CONNECTED`() {
        val comp = TestComponent()
        assertEquals(ConnectionState.DISCONNECTED, comp.state)
        comp.start()
        assertEquals(ConnectionState.CONNECTED, comp.state)
        comp.close()
        assertEquals(ConnectionState.CLOSED, comp.state)
    }

    @Test
    fun `close on fresh component is safe`() {
        val comp = TestComponent()
        assertEquals(ConnectionState.DISCONNECTED, comp.state)
        comp.close()
        assertEquals(ConnectionState.CLOSED, comp.state)
    }

    @Test
    fun `lifecycle callbacks are invoked on start`() {
        val comp = TestComponent()
        comp.start()
        assertTrue(comp.doStartCalled)
        assertTrue(comp.onConnectCalled)
        assertTrue(comp.onReadyCalled)
        comp.close()
        assertTrue(comp.doCloseCalled)
        assertTrue(comp.onStopCalled)
    }

    @Test
    fun `awaitTermination blocks until close`() {
        val comp = TestComponent()
        comp.start()

        val waiterStarted = CountDownLatch(1)
        val waiterReturned = CountDownLatch(1)
        val waiter = thread(
            start = true,
            isDaemon = true,
            name = "component-termination-waiter",
        ) {
            waiterStarted.countDown()
            comp.awaitTermination()
            waiterReturned.countDown()
        }

        comp.use {_ ->
            assertTrue(waiterStarted.await(1, TimeUnit.SECONDS))
            assertFalse(
                waiterReturned.await(100, TimeUnit.MILLISECONDS),
                "awaitTermination should block before close",
            )
        }

        assertTrue(
            waiterReturned.await(1, TimeUnit.SECONDS),
            "awaitTermination should return after close",
        )
        waiter.join(1_000)
        assertFalse(waiter.isAlive)
    }

    @Test
    fun `close during start prevents CONNECTED state`() {
        val comp = object : TestComponent() {
            override fun doStart() {
                close()
            }
        }
        comp.start()
        assertEquals(ConnectionState.CLOSED, comp.state)
    }

    @Test
    fun `multiple close calls are idempotent`() {
        val comp = TestComponent()
        comp.start()
        comp.close()
        comp.close()
        assertEquals(ConnectionState.CLOSED, comp.state)
        var stopCount = 0
        if (comp.onStopCalled) stopCount++
        assertEquals(1, stopCount)
    }

    @Test
    fun `reconnect attempts are bounded by maxReconnectAttempts`() = runBlocking {
        val callCount = AtomicInteger()
        val retriesAttempted = CountDownLatch(2)
        val comp = object : TestComponent(
            ComponentConfig(autoReconnect = true, maxReconnectAttempts = 2)
        ) {
            override fun isActive(): Boolean = false
            override fun doStart() {
                val attempt = callCount.incrementAndGet()
                if (attempt > 1) {
                    retriesAttempted.countDown()
                    throw RuntimeException("connection failed #$attempt")
                }
            }
        }
        comp.start()

        try {
            assertTrue(
                retriesAttempted.await(7, TimeUnit.SECONDS),
                "reconnect loop should retry doStart up to maxReconnectAttempts",
            )
            withTimeout(1_000) {
                while (comp.state != ConnectionState.DISCONNECTED) {
                    delay(10)
                }
            }
            delay(1_500)
            assertEquals(
                3,
                callCount.get(),
                "one initial start plus maxReconnectAttempts=2 retries expected",
            )
            assertEquals(ConnectionState.DISCONNECTED, comp.state)
        } finally {
            comp.close()
        }
    }

    @Test
    fun `close stops reconnection`() = runBlocking {
        val callCount = AtomicInteger()
        val comp = object : TestComponent(
            ComponentConfig(autoReconnect = true, maxReconnectAttempts = 10)
        ) {
            override fun isActive(): Boolean = false
            override fun doStart() {
                callCount.incrementAndGet()
            }
        }

        comp.start()
        withTimeout(2_500) {
            while (comp.state != ConnectionState.DISCONNECTED) {
                delay(10)
            }
        }
        comp.close()
        delay(1_200)

        assertEquals(1, callCount.get(), "doStart should not be called after close")
        assertEquals(ConnectionState.CLOSED, comp.state)
    }

    @Test
    fun `state property reflects connection lifecycle`() {
        val comp = TestComponent()
        assertEquals(ConnectionState.DISCONNECTED, comp.state)
        comp.start()
        assertEquals(ConnectionState.CONNECTED, comp.state)
        comp.close()
        assertEquals(ConnectionState.CLOSED, comp.state)
    }
}
