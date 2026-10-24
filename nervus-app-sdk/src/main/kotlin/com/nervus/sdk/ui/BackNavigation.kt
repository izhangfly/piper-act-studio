package com.nervus.sdk.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Dispatches one system Back request to the innermost enabled handler.
 *
 * The dispatcher belongs to one Compose window. Applications add handlers with
 * [NervusBackHandler]; the desktop window adapter invokes [dispatch] when the
 * system navigation bar delivers the Back key to the focused window.
 */
class BackDispatcher internal constructor(
    private val onUnhandledBack: () -> Boolean,
) {
    private val callbacks = mutableListOf<BackCallback>()

    internal fun register(callback: BackCallback) {
        callbacks += callback
    }

    internal fun unregister(callback: BackCallback) {
        callbacks -= callback
    }

    internal fun dispatch(): Boolean {
        val callback = callbacks.asReversed().firstOrNull { it.enabled }
            ?: return onUnhandledBack()
        callback.onBack()
        return true
    }
}

internal class BackCallback(
    var enabled: Boolean,
    var onBack: () -> Unit,
)

internal val LocalBackDispatcher = staticCompositionLocalOf<BackDispatcher?> { null }

/**
 * Registers an application-level Back action for the current Compose window.
 *
 * Nested handlers win over their parents because the most recently composed
 * enabled handler is dispatched first. Set [enabled] only while that UI state
 * can actually go back; once no handler is enabled, the window-level fallback
 * supplied to `attachComposeDesktop` runs.
 */
@Composable
fun NervusBackHandler(
    enabled: Boolean = true,
    onBack: () -> Unit,
) {
    val dispatcher = checkNotNull(LocalBackDispatcher.current) {
        "NervusBackHandler must be used inside attachComposeDesktop"
    }
    val currentOnBack = rememberUpdatedState(onBack)
    val callback = remember {
        BackCallback(
            enabled = enabled,
            onBack = { currentOnBack.value() },
        )
    }
    callback.enabled = enabled

    DisposableEffect(dispatcher, callback) {
        dispatcher.register(callback)
        onDispose { dispatcher.unregister(callback) }
    }
}
