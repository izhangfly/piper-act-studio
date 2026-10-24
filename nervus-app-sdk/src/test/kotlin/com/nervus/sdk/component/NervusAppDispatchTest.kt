package com.nervus.sdk.component

import com.nervus.sdk.annotations.Method
import com.nervus.sdk.ipc.dispatch.DispatchHandler
import com.nervus.sdk.runtime.ServiceStub
import io.github.nervusos.ipc.v1.CallerContext
import io.github.nervusos.ipc.v1.DispatchResult
import io.github.nervusos.ipc.v1.Failure
import io.github.nervusos.ipc.v1.StatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins "an @Method that registered successfully always receives its calls".
 *
 * # The bug these tests guard against hides extremely well
 *
 * registerProvidedInterfaces used to pass dispatchCallback to ServiceStub unconditionally, and the stub's
 * dispatch is either/or: given a callback it [only uses the callback] and never takes the @Method-annotation
 * path, invokeDirect. So NervusApp.dispatch's default implementation (falling back to NOT_FOUND) bypassed
 * every @Method.
 *
 * Symptom and root cause were far apart: the log clearly said "registered method id=2 name=openManager",
 * the kernel really delivered the Dispatch (Route succeeded, no refusal recorded), yet the caller got
 * NOT_FOUND. Everything looked fine except that the method body never ran.
 */
class NervusAppDispatchTest {

    /** An ordinary Provider implementation: annotations only, no dispatch override. */
    class Endpoint {
        var openCalls = 0
            private set

        @Method(id = 2)
        fun openManager(payload: ByteArray): ByteArray {
            openCalls += 1
            return ByteArray(0)
        }
    }

    /** A component that doesn't override dispatch, like nearly every app. */
    private class PlainApp : NervusApp() {
        override val requiredInterfaces = emptyList<InterfaceRequirement>()
    }

    /** A component that overrides dispatch: it chooses to take over all dispatching. */
    private class InterceptingApp : NervusApp() {
        override val requiredInterfaces = emptyList<InterfaceRequirement>()

        var intercepted = 0
            private set

        override fun dispatch(
            methodId: Int,
            payload: ByteArray,
            context: CallerContext,
        ): DispatchResult {
            intercepted += 1
            return DispatchResult.newBuilder()
                .setFailure(
                    Failure.newBuilder()
                        .setCode(StatusCode.STATUS_CODE_NOT_FOUND)
                        .setPublicMessage("intercepted")
                        .build()
                )
                .build()
        }
    }

    /** Calls the private dispatchInterceptor() by reflection; that's where this behaviour is implemented. */
    private fun interceptorOf(app: NervusApp): Any? {
        val m = NervusApp::class.java.getDeclaredMethod("dispatchInterceptor")
        m.isAccessible = true
        return m.invoke(app)
    }

    @Test
    fun `no interceptor without a dispatch override, so annotation dispatch works`() {
        assertEquals(
            null,
            interceptorOf(PlainApp()),
            "an interceptor was installed without a dispatch override; every @Method would miss its calls",
        )
    }

    @Test
    fun `interceptor installed only when dispatch is overridden`() {
        assertTrue(
            interceptorOf(InterceptingApp()) != null,
            "dispatch is overridden but no interceptor was installed; the subclass's takeover has no effect",
        )
    }

    /**
     * End to end: without a dispatchCallback, a dispatch must really run the method body.
     *
     * This is the part users can see; the two tests above only check the condition itself.
     */
    @Test
    fun `without an interceptor dispatch reaches the @Method body`() {
        val impl = Endpoint()
        val handler = DispatchHandler()
        ServiceStub().registerInterface(impl, handler, null)

        val result = handler.dispatch(
            io.github.nervusos.ipc.v1.Dispatch.newBuilder()
                .setRouteId(1)
                .setMethodId(2)
                .build()
        )

        assertEquals(1, impl.openCalls, "the method body didn't run; dispatch was bypassed")
        assertTrue(result.hasSuccess(), "expected success, got: ${result.failure}")
    }

    /** With an interceptor installed, the method body should [not] run: the subclass took over and forwards itself. */
    @Test
    fun `with an interceptor installed the interceptor takes over`() {
        val impl = Endpoint()
        val handler = DispatchHandler()
        var seen = 0
        ServiceStub().registerInterface(impl, handler) { _, _, _ ->
            seen += 1
            DispatchResult.newBuilder()
                .setFailure(
                    Failure.newBuilder()
                        .setCode(StatusCode.STATUS_CODE_NOT_FOUND)
                        .setPublicMessage("intercepted")
                        .build()
                )
                .build()
        }

        handler.dispatch(
            io.github.nervusos.ipc.v1.Dispatch.newBuilder()
                .setRouteId(1)
                .setMethodId(2)
                .build()
        )

        assertEquals(1, seen, "the interceptor didn't receive the call")
        assertEquals(0, impl.openCalls, "the method body shouldn't run directly while an interceptor is in place")
    }
}
