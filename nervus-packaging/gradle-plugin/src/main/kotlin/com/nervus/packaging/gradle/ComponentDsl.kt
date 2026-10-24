package com.nervus.packaging.gradle

import java.io.Serializable

class ComponentDsl : Serializable {

    private val _components = mutableListOf<ComponentSpec>()
    val components: List<ComponentSpec> get() = _components

    fun app(id: String, configure: AppComponentSpec.() -> Unit) {
        val spec = AppComponentSpec(id).apply(configure)
        _components.add(spec)
    }

    fun service(id: String, configure: ServiceComponentSpec.() -> Unit) {
        val spec = ServiceComponentSpec(id).apply(configure)
        _components.add(spec)
    }

    abstract class ComponentSpec(val id: String) : Serializable {
        var entry: String = ""
        var runtime: String = "jvm"
        var nativeLibDir: String? = null
        var mainClass: String? = null
        var launchMode: String = "on-demand"
        var criticality: String? = null
        var disableable: Boolean? = null
        var interfaces: List<String>? = null
        var idleTimeoutSec: Int? = null

        val exports: ExportDsl = ExportDsl()
        val limits: LimitsDsl = LimitsDsl()
    }

    class AppComponentSpec(id: String) : ComponentSpec(id) {
        init { launchMode = "manual" }
    }

    class ServiceComponentSpec(id: String) : ComponentSpec(id) {
        init { launchMode = "on-demand" }
    }
}
