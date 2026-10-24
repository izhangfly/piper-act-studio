package com.nervus.sdk.component

import com.nervus.sdk.ipc.NervusServiceHost
import com.nervus.sdk.runtime.ServiceStub
import io.github.nervusos.ipc.v1.CallerContext
import io.github.nervusos.ipc.v1.DispatchResult
import io.github.nervusos.ipc.v1.Failure
import io.github.nervusos.ipc.v1.StatusCode
import java.util.concurrent.TimeUnit

data class ProvidedInterface(
    val id: String,
    val major: Int,
    val minor: Int = 0,
    /**
     * The interface's implementation instance; methods are marked with the [com.nervus.sdk.annotations.Method] annotation.
     *
     * May be null: **interfaces with no methods exist**. The kernel used to have no IPC for "start an app",
     * so an app that could be opened had to export an interface for others to resolve and thereby start it;
     * that interface only needed a name, not a single method. Forcing an empty implementation on it
     * just to satisfy the type would make readers think it has behaviour.
     */
    val instance: Any? = null,
    val resourceHandle: String = "",
    val schemaHash: ByteArray = ByteArray(0),
)

abstract class NervusService(
    config: ComponentConfig = ComponentConfig()
) : Component(config) {

    private var host: NervusServiceHost? = null
    private val stubs = mutableListOf<ServiceStub>()
    private val endpointRegistrations = mutableListOf<EndpointRegistration>()

    protected abstract val providedInterfaces: List<ProvidedInterface>

    override fun isActive(): Boolean = host?.isConnected == true

    override fun doStart() {
        host?.close()
        val h = NervusServiceHost(
            sdkName = config.sdkName,
            sdkVersion = config.sdkVersion
        )
        h.connect(
            socketPath = config.socketPath,
            componentId = config.componentId,
            handshakeTimeoutMs = config.handshakeTimeoutMs
        )
        host = h
        registerProvidedInterfaces(h)
    }

    override fun doClose() {
        for (stub in stubs) {
            host?.let { stub.unregisterAll(it.handler) }
        }
        stubs.clear()
        host?.close()
        host = null
        endpointRegistrations.clear()
    }

    protected open fun dispatch(
        methodId: Int,
        payload: ByteArray,
        context: CallerContext
    ): DispatchResult {
        for (stub in stubs) {
            val result = stub.dispatch(methodId, payload, context)
            if (result != null) return result
        }
        return DispatchResult.newBuilder()
            .setFailure(Failure.newBuilder()
                .setCode(StatusCode.STATUS_CODE_NOT_FOUND)
                .setPublicMessage("no handler for method_id=$methodId")
                .build())
            .build()
    }

    private fun registerProvidedInterfaces(h: NervusServiceHost) {
        for (iface in providedInterfaces) {
            val future = h.registerEndpoint(
                interfaceId = iface.id,
                interfaceMajor = iface.major,
                interfaceMinor = iface.minor,
                schemaHash = iface.schemaHash,
                resourceHandle = iface.resourceHandle,
            )
            val registration: com.nervus.sdk.ipc.Registration
            try {
                registration = future.get(30, TimeUnit.SECONDS)
            } catch (e: Exception) {
                throw RuntimeException("failed to register interface '${iface.id}': ${e.message}", e)
            }
            logger.info("registered interface '${iface.id}' -> endpoint ${registration.endpointId}")

            // A placeholder interface with no methods (instance == null) only occupies a resolvable name
            // and has no handlers to attach
            iface.instance?.let { impl ->
                val stub = ServiceStub()
                stub.registerInterface(impl, h.handler) { methodId, payload, caller ->
                    dispatch(methodId, payload, caller)
                }
                stubs.add(stub)
            }
            endpointRegistrations.add(
                EndpointRegistration(
                    endpointId = registration.endpointId,
                    interfaceId = iface.id,
                    interfaceMajor = iface.major,
                    interfaceMinor = iface.minor
                )
            )
        }
    }

    private data class EndpointRegistration(
        val endpointId: Long,
        val interfaceId: String,
        val interfaceMajor: Int,
        val interfaceMinor: Int,
    )
}
