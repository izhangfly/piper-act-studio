package com.nervus.act.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.nervus.act.engine.EngineClient
import com.nervus.act.engine.Studio
import com.nervus.act.ui.StudioScreen
import com.nervus.sdk.component.ComponentConfig
import com.nervus.sdk.component.NervusApp
import com.nervus.sdk.ui.attachComposeDesktop

fun main(args: Array<String>) {
    if ("--standalone" in args) {
        // Development: a plain desktop window, no NervusOS kernel needed. Point it at any engine with
        // ACT_ENGINE (default http://127.0.0.1:8795), e.g. `python -m nervus_act --sim` on a laptop.
        val studio = Studio(EngineClient(System.getenv("ACT_ENGINE") ?: "http://127.0.0.1:8795"))
        studio.start()
        application {
            Window(onCloseRequest = { studio.close(); exitApplication() }, title = "ACT Studio",
                state = rememberWindowState(width = 1600.dp, height = 950.dp)) {
                StudioScreen(studio)
            }
        }
        return
    }
    val app = ActStudioApp()
    Runtime.getRuntime().addShutdownHook(Thread { app.close() })
    app.start()
}

/**
 * ACT Studio: teach the PiPER by demonstration, train on the Mac, run what it learned, correct it, and let
 * post-training improve it.
 *
 *     this app -(HTTP, loopback)-> ACT engine (nervus-act.service) -> PiPER driver + robot cameras
 *                                       |
 *                              ~/Shared/NervusACT (Syncthing: LAN, or the server relay)
 *                                       |
 *                                 Mac worker: build, train, gate, publish, promote
 *
 * The app holds no state of its own: everything it shows comes from the engine, so closing it loses nothing,
 * and a run in progress stops safely a few seconds after the app goes away.
 */
class ActStudioApp : NervusApp(
    config = ComponentConfig(componentId = "main", socketPath = "/run/nervus/nervud.sock"),
) {
    override val requiredInterfaces = emptyList<com.nervus.sdk.component.InterfaceRequirement>()

    private val studio = Studio()

    override fun onReady() {
        studio.start()
        attachComposeDesktop(title = "ACT Studio", width = 1600.dp, height = 900.dp) {
            StudioScreen(studio)
        }
    }

    override fun onStop() {
        studio.close()
    }
}
