package com.nervus.packaging.signing

import com.nervus.packaging.model.Lineage
import com.nervus.packaging.model.SigBlock
import com.nervus.packaging.model.Signature
import java.security.KeyPair
import java.security.MessageDigest
import java.security.Signature as JSignature
import java.util.Base64

object ManifestSigner {

    private const val NERVUS_PKG_MANIFEST_PREFIX = "nervus-pkg-manifest-v1\u0000"

    fun sign(
        manifestJsonBytes: ByteArray,
        signerKeyPair: KeyPair,
        role: String,
        lineage: Lineage? = null,
    ): SigBlock {
        val toSign = NERVUS_PKG_MANIFEST_PREFIX.toByteArray() + manifestJsonBytes

        val sig = JSignature.getInstance("Ed25519").apply {
            initSign(signerKeyPair.private)
            update(toSign)
        }
        val signatureBytes = sig.sign()

        // [It must be the raw 32-byte public key, not getEncoded()]
        //
        // JCA's PublicKey.getEncoded() gives the X.509 SubjectPublicKeyInfo DER,
        // 44 bytes for Ed25519 (the 12-byte prefix 302a300506032b6570032100 + the 32-byte raw key).
        // The kernel's keyIDOf (pkgregistry/signature.go) and the trust bundle generator
        // (key_id() in deploy/build-release.sh: openssl -outform DER | tail -c 32)
        // both take sha256 of the [raw 32 bytes].
        //
        // Computed over 44 bytes, the key_id never matches the trust bundle entry and verification fails with
        // ErrUntrustedSigner; scanSystemImage fails closed to Ordinary on a failed check rather than skipping the
        // package, so the package installs and runs, just with trust=ordinary, and trust levels silently stop working.
        // This happened on real hardware: the three system apps signed platform-systemapp were all ordinary,
        // while those signed platform-release (the Go sysmanifest hashes the raw key)
        // were platform.
        //
        // Likewise the key embedded for the developer role must be the raw 32 bytes: the kernel's decodePubKey
        // checks length == ed25519.PublicKeySize and rejects 44 bytes with wrong length.
        val rawPubKey = rawEd25519PublicKey(signerKeyPair.public.encoded)
        val sha256 = MessageDigest.getInstance("SHA-256")
        val keyId = "sha256:" + sha256.digest(rawPubKey).joinToString("") { "%02x".format(it) }

        val signature = Signature(
            role = role,
            alg = "ed25519",
            keyId = keyId,
            key = if (role == "developer") Base64.getEncoder().encodeToString(rawPubKey) else null,
            sig = Base64.getEncoder().encodeToString(signatureBytes),
        )

        return SigBlock(
            signatures = listOf(signature),
            lineage = lineage,
        )
    }

    /**
     * Extracts the raw 32-byte Ed25519 public key from X.509 SubjectPublicKeyInfo DER.
     *
     * An Ed25519 SPKI is a fixed 44 bytes with a fixed prefix:
     * ```text
     * 30 2a           SEQUENCE, 42 bytes
     *   30 05         SEQUENCE, 5 bytes (AlgorithmIdentifier)
     *     06 03 2b 65 70   OID 1.3.101.112 = id-Ed25519
     *   03 21 00      BIT STRING, 33 bytes, 0 unused bits
     *     <32-byte public key>
     * ```
     * The length is fixed, so check the prefix and take the tail; no DER parser needed. Input that is already
     * 32 bytes (some Providers hand over the raw key) is returned unchanged.
     */
    private fun rawEd25519PublicKey(encoded: ByteArray): ByteArray {
        if (encoded.size == RAW_PUBKEY_LEN) return encoded
        require(encoded.size == SPKI_LEN && encoded.copyOfRange(0, SPKI_PREFIX.size).contentEquals(SPKI_PREFIX)) {
            "unrecognised Ed25519 public key encoding: ${encoded.size} bytes; expected raw $RAW_PUBKEY_LEN or X.509 SPKI $SPKI_LEN"
        }
        return encoded.copyOfRange(SPKI_LEN - RAW_PUBKEY_LEN, SPKI_LEN)
    }

    private const val RAW_PUBKEY_LEN = 32
    private const val SPKI_LEN = 44

    private val SPKI_PREFIX = byteArrayOf(
        0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x03, 0x21, 0x00,
    )
}