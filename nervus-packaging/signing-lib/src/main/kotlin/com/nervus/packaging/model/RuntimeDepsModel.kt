package com.nervus.packaging.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RuntimeDeps(
    @SerialName("min_java_release")
    val minJavaRelease: Int? = null,
)
