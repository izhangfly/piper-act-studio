package com.nervus.sdk.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackNavigationTest {
    @Test
    fun newestEnabledHandlerWins() {
        val calls = mutableListOf<String>()
        val dispatcher = BackDispatcher {
            calls += "fallback"
            true
        }
        val outer = BackCallback(enabled = true) { calls += "outer" }
        val inner = BackCallback(enabled = true) { calls += "inner" }
        dispatcher.register(outer)
        dispatcher.register(inner)

        assertTrue(dispatcher.dispatch())
        assertEquals(listOf("inner"), calls)

        inner.enabled = false
        assertTrue(dispatcher.dispatch())
        assertEquals(listOf("inner", "outer"), calls)
    }

    @Test
    fun unhandledBackUsesWindowFallback() {
        val dispatcher = BackDispatcher { false }

        assertFalse(dispatcher.dispatch())
    }
}
