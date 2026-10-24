package com.nervus.sdk.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.nervus.sdk.component.Component
import com.nervus.sdk.ipc.ConnectionState
import java.util.logging.Logger
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val composeWindowLog = Logger.getLogger("com.nervus.sdk.ui.DesktopCompose")

/**
 * Attaches a Compose Desktop window to a component that has already started.
 *
 * The receiver is [Component] rather than [NervusApp]: components like the desktop and Settings may provide interfaces
 * (so they can be woken by Resolve) and also have a UI; which base class they use depends on what they mostly do,
 * and "can it have a window" shouldn't be tied to "is it a consumer". The window only uses close() and
 * state, both on Component.
 */
fun Component.attachComposeDesktop(
    title: String = "",
    width: Dp = 800.dp,
    height: Dp = 600.dp,
    onDisconnect: () -> Unit = {},
    onUnhandledBack: () -> Boolean = { false },
    /**
     * Window handle, so the app can tell "hide myself" from "close myself".
     *
     * Pass in a [NervusWindow] and this function connects it to the real window; the app can then call
     * [NervusWindow.hide] / [NervusWindow.show] / [NervusWindow.close].
     *
     * **Don't use an X11 minimise to "hide yourself"**: here that makes Compose fire
     * `onCloseRequest`, so hiding turns into ending the process. See NervusWindow.
     */
    windowHandle: NervusWindow? = null,
    content: @Composable () -> Unit
) {
    val app = this

    thread(name = "compose-$title", isDaemon = false) {
        application {
            val backDispatcher = remember(onUnhandledBack) {
                BackDispatcher(onUnhandledBack)
            }
            // Make window.close() take the same path as the user pressing close: disconnect the control plane and end the process.
            // exitApplication is only available inside the application scope.
            remember(windowHandle) {
                windowHandle?._closer = {
                    app.close()
                    exitApplication()
                }
                true
            }
            val visible = windowHandle?.visible?.collectAsState()?.value ?: true
            Window(
                onCloseRequest = {
                    app.close()
                    exitApplication()
                },
                visible = visible,
                title = title,
                // Nervus is a single-foreground-window environment. Requesting
                // maximization here also removes the fixed-size dependency when
                // the panel is not 1280x800; Openbox's right margin remains part
                // of the maximized work area.
                state = rememberWindowState(
                    placement = WindowPlacement.Maximized,
                    width = width,
                    height = height,
                ),
                onPreviewKeyEvent = { event ->
                    event.type == KeyEventType.KeyDown &&
                        event.key == Key.Escape &&
                        backDispatcher.dispatch()
                },
            ) {
                NervusTheme {
                    CompositionLocalProvider(LocalBackDispatcher provides backDispatcher) {
                        content()
                    }

                    LaunchedEffect(Unit) {
                        // On Linux Skiko starts with OpenGL but can fall back to
                        // software. Record the API actually selected so the
                        // component journal tells us which path is in use.
                        delay(1_000)
                        composeWindowLog.info(
                            "compose window '$title' renderer=${window.renderApi}"
                        )
                        while (isActive) {
                            delay(500)
                            if (app.state == ConnectionState.DISCONNECTED) {
                                onDisconnect()
                                break
                            }
                        }
                    }
                }
            }
        }
    }
}
