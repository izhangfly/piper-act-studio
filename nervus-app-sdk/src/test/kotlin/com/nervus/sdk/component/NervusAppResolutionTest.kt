package com.nervus.sdk.component

import com.nervus.sdk.runtime.ResolvedEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class NervusAppResolutionTest {
    private class OptionalInterfaceApp : NervusApp() {
        override val requiredInterfaces = listOf(
            InterfaceRequirement(
                id = "nervus.interface.optional",
                isRequired = false,
            )
        )
    }

    @Test
    fun optionalInterfaceCanResolveAfterAnEarlierAttemptFailed() {
        val app = OptionalInterfaceApp()
        var attempts = 0

        val firstFailure = assertFailsWith<RuntimeException> {
            app.resolveDeclaredEndpoint("nervus.interface.optional") {
                attempts += 1
                throw RuntimeException("provider not registered yet")
            }
        }
        assertEquals("provider not registered yet", firstFailure.message)

        val resolved = ResolvedEndpoint(
            endpointId = 42,
            interfaceId = "nervus.interface.optional",
            interfaceMajor = 1,
            interfaceMinor = 0,
        )
        val retried = app.resolveDeclaredEndpoint("nervus.interface.optional") {
            attempts += 1
            resolved
        }
        val cached = app.resolveDeclaredEndpoint("nervus.interface.optional") {
            error("cached endpoint should be reused")
        }

        assertSame(resolved, retried)
        assertSame(resolved, cached)
        assertEquals(2, attempts)
    }

    @Test
    fun undeclaredInterfaceIsReportedSeparately() {
        val app = OptionalInterfaceApp()

        val failure = assertFailsWith<IllegalStateException> {
            app.resolveDeclaredEndpoint("nervus.interface.missing") {
                error("undeclared interfaces must not be resolved")
            }
        }

        assertEquals(
            "interface 'nervus.interface.missing' is not declared in requiredInterfaces",
            failure.message,
        )
    }
}
