package com.nervus.packaging.validation

import com.nervus.packaging.model.Component
import com.nervus.packaging.model.ComponentType
import com.nervus.packaging.model.LaunchMode

object ComponentValidator {

    private val ID_REGEX = Regex("^[a-z][a-z0-9_]*$")
    private const val MAX_ID_LENGTH = 64

    fun validate(component: Component, allIds: Set<String>): List<String> {
        val errors = mutableListOf<String>()

        if (!ID_REGEX.matches(component.id)) {
            errors.add("component id '${component.id}' must match $ID_REGEX")
        }
        if (component.id.length > MAX_ID_LENGTH) {
            errors.add("component id length ${component.id.length} exceeds max $MAX_ID_LENGTH")
        }
        if (component.id in allIds) {
            errors.add("duplicate component id '${component.id}'")
        }

        if (component.type == ComponentType.app && component.launchMode == LaunchMode.always_on) {
            errors.add("type=app cannot declare launch_mode=always-on")
        }
        if (component.type == ComponentType.service && component.launchMode == LaunchMode.manual) {
            errors.add("type=service cannot declare launch_mode=manual")
        }

        if (component.idleTimeoutSec != null && component.launchMode != LaunchMode.on_demand) {
            errors.add("idle_timeout_sec is only valid for on-demand components")
        }

        return errors
    }
}
