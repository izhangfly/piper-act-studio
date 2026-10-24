package com.nervus.act

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import com.nervus.act.engine.EngineClient
import com.nervus.act.engine.Studio
import com.nervus.act.engine.Tab
import com.nervus.act.engine.arr
import com.nervus.act.engine.get
import com.nervus.act.engine.int
import com.nervus.act.engine.str
import com.nervus.act.ui.StudioScreen
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Timeout

/**
 * Drives ACT Studio's real screens against a running engine, the way a person would: by the words on the
 * buttons. Start a simulated engine first and point the test at it:
 *
 *     PYTHONPATH=act/engine python -m nervus_act --sim --shared /tmp/acttest/NervusACT --port 8899
 *     ACT_ENGINE_TEST=http://127.0.0.1:8899 ./gradlew test
 *
 * Without ACT_ENGINE_TEST the test is skipped. On a failure the screen is saved to
 * build/reports/ui-test/failure.png.
 */
@OptIn(ExperimentalTestApi::class)
class StudioUiTest {
    private val base = System.getenv("ACT_ENGINE_TEST").orEmpty()

    /** Panel titles and the nav are drawn in capitals, so text is matched regardless of case. */
    private fun SemanticsNodeInteractionsProvider.seen(text: String) =
        onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()

    /**
     * Waits for [condition] while the UI and the engine run, and fails with [what] when it does not come true.
     *
     * Not the library's waitUntil: in Compose Multiplatform 1.7.3 that one builds its timeout message and
     * drops it, so a condition that never came true spun forever (rendering a frame per check, one core flat
     * out) instead of failing the test. Between checks this lets the UI settle and sleeps, because what the
     * test waits for (the engine recording, saving, listing) happens in real time, not in frames.
     */
    private fun SkikoComposeUiTest.await(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out after $timeoutMs ms waiting for $what")
            waitForIdle()
            Thread.sleep(40)
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun recordATakeAndQueueATrainingJob() {
        if (base.isEmpty()) return
        val engine = EngineClient(base)
        // A run killed halfway leaves its dataset open in the engine; start from a closed recorder.
        when (engine.get("/api/status")["recorder"]["state"].str) {
            "recording" -> {
                engine.post("/api/record/stop", mapOf("discard" to true))
                engine.post("/api/record/close", timeoutMs = 120_000)
            }
            "idle", "error" -> engine.post("/api/record/close", timeoutMs = 120_000)
        }
        val studio = Studio(engine, previews = false, pollMs = 5_000)
        val name = "ui_${System.currentTimeMillis() % 100000}"
        studio.start()
        try {
            runSkikoComposeUiTest {
                try {
                    setContent { StudioScreen(studio) }
                    await("the engine and the top bar", 15_000) { studio.connected && seen("ACT STUDIO") }
                    assertTrue(seen("SIMULATED ARM"), "the top bar says the arm is simulated")

                    // Record: open a dataset, take one drag-taught take.
                    onNodeWithText("RECORD").performClick()
                    await("the dataset fields", 5_000) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 2 }
                    onAllNodes(hasSetTextAction())[0].performTextInput(name)
                    onAllNodes(hasSetTextAction())[1].performTextInput("move the arm out and back")
                    onNodeWithText("Open").performClick()
                    await("START to be enabled (dataset open)", 60_000) {
                        onAllNodes(hasText("START  (space)") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
                    }
                    val sim = EngineClient(studio.status["engine"]["sim_driver"].str ?: error("the engine is not running --sim"))
                    sim.post("/sim/teach", mapOf("on" to true))
                    onNodeWithText("START  (space)").performClick()
                    await("the recorder to start", 10_000) { studio.status["recorder"]["state"].str == "recording" }
                    // Move the simulated arm by hand for about three seconds.
                    for (i in 0..60) {
                        val w = i / 60.0
                        sim.post("/sim/drag", mapOf("joints" to listOf(20 * w, 45 + 20 * w, -60 - 20 * w, 0, 45, 0), "gripper_mm" to 10 + 30 * w))
                        Thread.sleep(50)
                    }
                    await("STOP to show", 10_000) { seen("STOP  (space)") }
                    onNodeWithText("STOP  (space)").performClick()
                    await("the take to be saved", 60_000) {
                        studio.status["recorder"]["saved_episodes"].int == 1 && studio.status["recorder"]["state"].str == "idle"
                    }
                    sim.post("/sim/teach", mapOf("on" to false))
                    await("the saved message", 10_000) { seen("Saved episode 1") }
                    onNodeWithText("Close").performClick()
                    await("the dataset to close", 60_000) { studio.status["recorder"]["state"].str == "closed" }

                    // Data: the dataset and its take are listed.
                    onNodeWithText("DATA").performClick()
                    // The list is lazy: on an engine with many datasets this one may be below the fold.
                    await("the dataset list", 10_000) { onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() }
                    onAllNodes(hasScrollToIndexAction())[0].performScrollToNode(hasText(name))
                    onNodeWithText(name).performClick()
                    await("the takes table", 20_000) { seen("Takes in $name") }

                    // Train: queue a job for the Mac.
                    onNodeWithText("TRAIN").performClick()
                    await("the new job form", 5_000) { seen("New training job") }
                    onAllNodes(hasSetTextAction())[0].performTextInput("$name-job")
                    // The dataset's checkbox row ("<name>  ·  1 takes  ·  scene+wrist"), not the job name field.
                    onNode(hasText("$name  ·", substring = true)).performScrollTo().performClick()
                    onNodeWithText("Send to the Mac").performScrollTo().performClick()
                    await("the job in the engine's list", 15_000) { studio.jobs.any { it["job"]["name"].str == "$name-job" } }
                    val job = studio.jobs.first { it["job"]["name"].str == "$name-job" }
                    assertEquals(listOf(name), job["job"]["datasets"].arr.map { it.str })
                    await("the job on the Train tab", 10_000) { seen(job["id"].str ?: "$name-job") }

                    // The other tabs render with the engine's data: each marker is on its own tab only.
                    for ((t, marker) in listOf(
                        Tab.Policies to "Promotion is automatic only on real results",
                        Tab.Run to "Scene check",
                        Tab.PostTrain to "Post-train now",
                        Tab.System to "Syncthing link to the Mac",
                        Tab.Overview to "Jobs, datasets and policies travel through the shared folder",
                    )) {
                        onNodeWithText(t.label.uppercase()).performClick()
                        await("the ${t.label} tab", 5_000) { seen(marker) }
                    }
                    assertTrue(seen("Mac worker"), "the overview shows the Mac worker")
                } catch (e: Throwable) {
                    // Not after a JUnit timeout: capturing waits for the UI, which may be what never settled.
                    if (e is InterruptedException || e.cause is InterruptedException) throw e
                    runCatching {
                        val out = File("build/reports/ui-test/failure.png").absoluteFile
                        out.parentFile.mkdirs()
                        ImageIO.write(captureToImage().toAwtImage(), "png", out)
                        System.err.println("UI test failed; the screen is in $out")
                    }
                    throw e
                }
            }
        } finally {
            studio.close()
        }
    }
}
