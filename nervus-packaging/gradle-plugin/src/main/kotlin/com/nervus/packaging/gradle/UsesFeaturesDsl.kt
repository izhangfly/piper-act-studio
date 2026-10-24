package com.nervus.packaging.gradle

import java.io.Serializable

class UsesFeaturesDsl : Serializable {

    private val _features = mutableListOf<UsesFeatureSpec>()
    val features: List<UsesFeatureSpec> get() = _features

    fun register(id: String, configure: UsesFeatureSpec.() -> Unit) {
        val spec = UsesFeatureSpec(id).apply(configure)
        _features.add(spec)
    }

    class UsesFeatureSpec(val id: String) : Serializable {
        var required: Boolean = false
    }
}
