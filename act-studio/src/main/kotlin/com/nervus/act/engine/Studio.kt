package com.nervus.act.engine

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

enum class Tab(val label: String) {
    Overview("Overview"), Record("Record"), Data("Data"), Train("Train"), Policies("Policies"),
    Run("Run"), PostTrain("Post-train"), System("System"),
}

data class Notice(val text: String, val error: Boolean, val at: Long = System.currentTimeMillis())

/**
 * Everything the screen shows, refreshed from the engine in the background.
 *
 * Status comes twice a second; the lists (datasets, policies, jobs, trials, rollouts) every few seconds, and
 * at once after an action that changes them. While the app is open it also sends the run heartbeat: the
 * engine stops a running policy if the app goes quiet, and releases the cameras a minute after it closes.
 */
class Studio(
    val engine: EngineClient = EngineClient(),
    private val log: Logger = Logger.getLogger("act-studio"),
    /** Live camera previews; tests turn them off (a preview redraws forever, so the UI is never idle). */
    val previews: Boolean = true,
    /** How often status is fetched. */
    private val pollMs: Long = 500,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var tab by mutableStateOf(Tab.Overview)
    var status by mutableStateOf<JsonElement?>(null)
        private set
    var connected by mutableStateOf(false)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<Notice?>(null)
    var busy by mutableStateOf<String?>(null)
        private set

    var datasets by mutableStateOf<List<JsonElement>>(emptyList())
        private set
    var policies by mutableStateOf<List<JsonElement>>(emptyList())
        private set
    var jobs by mutableStateOf<List<JsonElement>>(emptyList())
        private set
    var trials by mutableStateOf<List<JsonElement>>(emptyList())
        private set
    var rollouts by mutableStateOf<List<JsonElement>>(emptyList())
        private set

    private var listsDue = 0L

    fun start() {
        scope.launch {
            while (isActive) {
                try {
                    status = engine.get("/api/status", 3_000)
                    connected = true
                    lastError = null
                } catch (e: EngineException) {
                    connected = false
                    lastError = e.message
                }
                if (connected && System.currentTimeMillis() >= listsDue) refreshLists()
                // Every status change redraws the window (in software, on the Dell), so poll fast only while
                // something is moving: a take, a run, a move to the start pose.
                val active = status["recorder"]["state"].str in setOf("recording", "saving") ||
                    status["runner"]["state"].str in setOf("running", "moving", "loading")
                delay(if (active) pollMs else maxOf(pollMs, 1_500))
            }
        }
        scope.launch {
            while (isActive) {
                try {
                    engine.post("/api/heartbeat", timeoutMs = 2_000)
                } catch (_: EngineException) {
                }
                delay(1_000)
            }
        }
    }

    fun refreshSoon() {
        listsDue = 0L
    }

    private fun refreshLists() {
        listsDue = System.currentTimeMillis() + 4_000
        try {
            datasets = engine.get("/api/datasets").arr
            policies = engine.get("/api/policies").arr
            jobs = engine.get("/api/jobs").arr
            trials = engine.get("/api/trials").arr
            rollouts = engine.get("/api/rollouts").arr
        } catch (e: EngineException) {
            log.fine("list refresh: ${e.message}")
        }
    }

    /**
     * Run one action off the UI thread and say how it went: the engine's own message when it gives one.
     * Only one action at a time; a second press while one is in flight is ignored rather than queued.
     */
    fun act(label: String, success: String? = null, block: EngineClient.() -> JsonElement?) {
        if (busy != null) return
        busy = label
        scope.launch {
            try {
                val r = engine.block()
                val msg = r["message"].str ?: success
                if (msg != null) notice = Notice(msg, false)
                refreshSoon()
            } catch (e: EngineException) {
                notice = Notice("$label: ${e.message}", true)
            } catch (e: Exception) {
                notice = Notice("$label: ${e.message ?: e.javaClass.simpleName}", true)
                log.warning("$label failed: $e")
            } finally {
                busy = null
            }
        }
    }

    /** Safety actions skip the one-at-a-time rule: STOP and E-STOP must work while anything else is in flight. */
    fun now(label: String, block: EngineClient.() -> Unit) {
        scope.launch {
            try {
                engine.block()
                notice = Notice(label, false)
            } catch (e: EngineException) {
                notice = Notice("$label failed: ${e.message}", true)
            }
        }
    }

    /** Fetch something for one screen, off the UI thread. */
    fun <T> load(block: EngineClient.() -> T, done: (T) -> Unit) {
        scope.launch {
            try {
                done(engine.block())
            } catch (e: EngineException) {
                notice = Notice(e.message ?: "request failed", true)
            }
        }
    }

    fun close() {
        scope.cancel()
    }
}
