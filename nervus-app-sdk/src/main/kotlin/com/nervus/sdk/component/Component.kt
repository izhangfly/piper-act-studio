package com.nervus.sdk.component

import com.nervus.sdk.ipc.ConnectionState
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.logging.Logger

data class ComponentConfig(
    val socketPath: String = "/run/nervus/nervud.sock",
    val sdkName: String = "nervus-app-sdk",
    val sdkVersion: String = "0.1.0",
    val handshakeTimeoutMs: Long = 5000,
    val autoReconnect: Boolean = true,
    val maxReconnectAttempts: Int = 5,
    val componentId: String = "",
)

abstract class Component(
    protected val config: ComponentConfig = ComponentConfig()
) : AutoCloseable {

    @Volatile
    var state: ConnectionState = ConnectionState.DISCONNECTED
        protected set

    @Volatile
    private var _closed = false

    private val closeLatch = CountDownLatch(1)
    private var scope: CoroutineScope? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0

    protected val logger = Logger.getLogger(this::class.java.name)

    protected open fun onReady() {}

    protected open fun onStop() {}

    protected open fun onConnect() {}

    protected abstract fun isActive(): Boolean

    protected abstract fun doStart()

    protected abstract fun doClose()

    fun start() {
        _closed = false
        state = ConnectionState.CONNECTING
        doStart()
        if (_closed) {
            doClose()
            return
        }
        state = ConnectionState.CONNECTED
        onConnect()
        startReconnectMonitor()
        onReady()
    }

    /**
     * Blocks the calling thread until [close] is invoked.
     *
     * [start] returns after the component is ready so GUI applications and
     * components with their own run loop can continue initialization. A
     * headless component whose main thread would otherwise return can call
     * this method explicitly after installing its shutdown hook.
     */
    fun awaitTermination() {
        closeLatch.await()
    }

    override fun close() {
        if (_closed) return
        _closed = true
        closeLatch.countDown()
        state = ConnectionState.CLOSED
        onStop()
        reconnectJob?.cancel()
        scope?.cancel()
        doClose()
    }

    private fun startReconnectMonitor() {
        if (!config.autoReconnect) return
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        reconnectJob = scope?.launch {
            while (!_closed) {
                delay(1000)
                if (_closed) break
                if (state == ConnectionState.CONNECTED && !isActive()) {
                    state = ConnectionState.DISCONNECTED
                    reconnectAttempts = 0
                    logger.info("connection lost, attempting reconnect")
                }
                if (state == ConnectionState.DISCONNECTED && reconnectAttempts < config.maxReconnectAttempts) {
                    val delayMs = backoffDelay(reconnectAttempts)
                    logger.info("reconnecting in ${delayMs}ms (attempt ${reconnectAttempts + 1}/${config.maxReconnectAttempts})")
                    delay(delayMs)
                    if (_closed) break
                    state = ConnectionState.CONNECTING
                    try {
                        doStart()
                        if (_closed) {
                            doClose()
                            return@launch
                        }
                        state = ConnectionState.CONNECTED
                        onConnect()
                        reconnectAttempts = 0
                        logger.info("reconnected successfully")
                    } catch (e: Exception) {
                        reconnectAttempts++
                        if (!_closed) {
                            state = ConnectionState.DISCONNECTED
                        }
                        logger.warning("reconnect attempt ${reconnectAttempts} failed: ${e.message}")
                    }
                }
            }
        }
    }

    private fun backoffDelay(attempt: Int): Long {
        val base = 1000L
        val maxDelay = 30000L
        val exp = 1L shl attempt.coerceAtMost(5)
        return (base * exp).coerceAtMost(maxDelay)
    }
}
