package com.nervus.packaging.model

import kotlinx.serialization.Serializable

@Serializable
data class UsesFeature(
    val id: String,
    val required: Boolean = false,
)
