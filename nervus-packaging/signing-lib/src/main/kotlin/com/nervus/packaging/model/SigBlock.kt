package com.nervus.packaging.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SigBlock(
    val format: Int = 1,
    val signatures: List<Signature>,
    val lineage: Lineage? = null,
)

@Serializable
data class Signature(
    val role: String,
    val alg: String = "ed25519",
    @SerialName("key_id")
    val keyId: String,
    val key: String? = null,
    val sig: String,
)
