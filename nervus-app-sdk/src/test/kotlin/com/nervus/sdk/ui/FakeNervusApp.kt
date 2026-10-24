package com.nervus.sdk.ui

import com.nervus.sdk.component.InterfaceRequirement
import com.nervus.sdk.component.NervusApp
import com.nervus.sdk.ipc.ConnectionState

class FakeNervusApp : NervusApp() {
    override val requiredInterfaces: List<InterfaceRequirement> = emptyList()

    private var _active = false

    override fun isActive(): Boolean = _active

    override fun doStart() {
        _active = true
        state = ConnectionState.CONNECTED
    }

    override fun doClose() {
        _active = false
        state = ConnectionState.CLOSED
    }
}
