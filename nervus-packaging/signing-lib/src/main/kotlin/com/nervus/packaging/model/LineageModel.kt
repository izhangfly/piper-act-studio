package com.nervus.packaging.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Lineage(
    val format: Int = 1,
    val nodes: List<LineageNode>,
)

@Serializable
data class LineageNode(
    @SerialName("key_id")
    val keyId: String,
    val key: String,
    @SerialName("signed_by_prev")
    val signedByPrev: String? = null,
)
