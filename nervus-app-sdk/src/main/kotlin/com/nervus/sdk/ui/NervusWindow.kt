package com.nervus.sdk.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Three fates of a Compose window, expressed separately.
 *
 * # Why this class exists
 *
 * There used to be only two paths: `onCloseRequest` (closing the window = ending the process) and using X11's
 * `xdotool windowminimize` as "hide". Here the latter makes Compose fire
 * `onCloseRequest`, so **"hiding myself" actually killed the process**.
 *
 * The symptom was hard to trace back: an on-demand component hid its window and the process was gone, the caller's
 * cached endpoint went dead and the next call got NOT_FOUND; then once it was started again by Resolve,
 * it popped up a fresh fullscreen window, so users saw "the window won't close".
 *
 * The three are genuinely different:
 *
 *   [hide]  the window disappears and **the process keeps running**. Registered endpoints stay valid, and the next wake-up
 *           just calls [show] without paying for another Compose cold start.
 *   [show]  show it again.
 *   [close] the user really wants to close this app: disconnect the control plane and end the process.
 *
 * # Hiding uses Compose's visible flag, not X11
 *
 * `Window(visible = false)` is managed by Compose itself without going through the window manager, so it isn't affected by
 * differences in Openbox/xdotool behaviour and doesn't fire `onCloseRequest`. The X11 route
 * (wmctrl/xdotool) is kept for cross-process jobs like "bring someone else's window to the front".
 */
class NervusWindow {
    private val _visible = MutableStateFlow(true)

    /** Whether the window is visible right now. Collected on the Compose side. */
    val visible: StateFlow<Boolean> get() = _visible

    /**
     * Installed by [attachComposeDesktop]: the action that really ends the process.
     *
     * Public because the SDK and the app are separate compilation units and `internal` can't cross that. The underscore
     * prefix marks it as not for apps; an app closes its window with [close].
     */
    @Volatile
    var _closer: (() -> Unit)? = null

    /**
     * Hides the window **without ending the process**.
     *
     * For "step aside when done": after a permission request is answered the UI should get out of the way, but the process should stay,
     * because the endpoint it registered is still valid and the next request can reuse it.
     */
    fun hide() {
        _visible.value = false
    }

    /**
     * Shows the window again.
     *
     * Called when an on-demand component is woken again. **This step is required**: a live process means the kernel won't
     * start it again, so no new window appears by itself; without show, nothing seems to happen to the caller.
     */
    fun show() {
        _visible.value = true
    }

    /** A snapshot read of visibility, for checks from non-Compose threads. */
    val isVisible: Boolean get() = _visible.value

    /**
     * Closes the app: disconnects the control plane and ends the process.
     *
     * The difference from [hide] is **intent**: here the user wants to quit the app, rather than the UI stepping aside for a while.
     */
    fun close() {
        _closer?.invoke()
    }
}
