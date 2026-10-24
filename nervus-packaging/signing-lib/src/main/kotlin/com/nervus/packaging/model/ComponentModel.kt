package com.nervus.packaging.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Component(
    val id: String,
    val type: ComponentType,
    val runtime: RuntimeType,
    val entry: String,
    @SerialName("native_lib_dir")
    val nativeLibDir: String? = null,
    @SerialName("launch_mode")
    val launchMode: LaunchMode,
    val criticality: Criticality? = null,
    val disableable: Boolean? = null,
    val exports: List<Export>? = null,
    val interfaces: List<String>? = null,
    @SerialName("idle_timeout_sec")
    val idleTimeoutSec: Int? = null,
    val limits: ResourceLimits? = null,
)

@Serializable
enum class ComponentType {
    app,
    service,
}

@Serializable
enum class RuntimeType {
    native,
    jvm,
}

@Serializable
enum class LaunchMode {
    @SerialName("always-on")
    always_on,
    @SerialName("on-demand")
    on_demand,
    manual,
}

@Serializable
enum class Criticality {
    optional,
    required,
    vital,
}

@Serializable
data class Export(
    val `interface`: String,
    val visibility: String,
)
