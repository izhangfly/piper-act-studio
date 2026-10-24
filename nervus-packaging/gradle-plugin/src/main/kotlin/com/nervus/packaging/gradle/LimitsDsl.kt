package com.nervus.packaging.gradle

import java.io.Serializable

class LimitsDsl : Serializable {
    var memoryMaxMb: Int? = null
    var cpuQuotaPercent: Int? = null
    var tasksMax: Int? = null
}
