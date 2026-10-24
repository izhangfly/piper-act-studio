package com.nervus.sdk.ui

import com.nervus.sdk.ipc.ConnectionState
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse

class DesktopComposeTest {
    private fun findThread(prefix: String, timeoutMs: Long = 200): Thread? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val thread = Thread.getAllStackTraces().keys.find { it.name.startsWith(prefix) }
            if (thread != null) return thread
            Thread.sleep(10)
        }
        return null
    }

    @Test
    fun `extension function is defined on NervusApp`() {
        val klass = Class.forName("com.nervus.sdk.ui.DesktopComposeKt")
        val methodNames = klass.declaredMethods.map { it.name }
        assertTrue(methodNames.any { it.startsWith("attachComposeDesktop") })
    }

    @Test
    fun `call starts a background thread with expected name`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Skipping: no display available")

        val app = FakeNervusApp()
        app.start()

        val threadName = "TestWindow"
        app.attachComposeDesktop(title = threadName) { }

        val composeThread = findThread("compose-$threadName")
        assertNotNull(composeThread, "Expected a thread named 'compose-$threadName'")

        app.close()
    }

    @Test
    fun `component close sets state to CLOSED`() {
        val app = FakeNervusApp()
        app.start()
        assertEquals(ConnectionState.CONNECTED, app.state)

        app.close()
        assertEquals(ConnectionState.CLOSED, app.state)
    }
}
