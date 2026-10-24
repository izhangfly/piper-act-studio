package com.nervus.packaging.signing

import com.nervus.packaging.model.SigBlock
import com.nervus.packaging.model.Signature
import java.security.KeyPair
import java.security.MessageDigest
import java.security.Signature as JSignature
import java.util.Base64

object Countersigner {

    private const val NERVUS_PKG_MANIFEST_PREFIX = "nervus-pkg-manifest-v1\u0000"

    fun addCountersign(
        sigBlock: SigBlock,
        manifestJsonBytes: ByteArray,
        countersignKeyPair: KeyPair,
        role: String,
    ): SigBlock {
        val toSign = NERVUS_PKG_MANIFEST_PREFIX.toByteArray() + manifestJsonBytes

        val sig = JSignature.getInstance("Ed25519").apply {
            initSign(countersignKeyPair.private)
            update(toSign)
        }
        val signatureBytes = sig.sign()

        val pubKeyEncoded = countersignKeyPair.public.encoded
        val sha256 = MessageDigest.getInstance("SHA-256")
        val keyId = "sha256:" + sha256.digest(pubKeyEncoded).joinToString("") { "%02x".format(it) }

        val newSignature = Signature(
            role = role,
            alg = "ed25519",
            keyId = keyId,
            key = null,
            sig = Base64.getEncoder().encodeToString(signatureBytes),
        )

        return sigBlock.copy(
            signatures = sigBlock.signatures + newSignature,
        )
    }
}