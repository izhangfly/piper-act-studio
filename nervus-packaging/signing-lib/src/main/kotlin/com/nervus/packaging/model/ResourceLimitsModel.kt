package com.nervus.packaging.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ResourceLimits(
    @SerialName("memory_max_mb")
    val memoryMaxMb: Int? = null,
    @SerialName("cpu_quota_percent")
    val cpuQuotaPercent: Int? = null,
    @SerialName("tasks_max")
    val tasksMax: Int? = null,
)
