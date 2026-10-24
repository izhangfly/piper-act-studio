package com.nervus.packaging.signing

import com.nervus.packaging.model.Signature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.security.KeyPair
import java.security.KeyPairGenerator

class ManifestSignerTest {

    private fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        return kpg.generateKeyPair()
    }

    @Test
    fun `sign produces valid signature block`() {
        val keyPair = generateKeyPair()
        val manifestBytes = """{"package_id":"com.example.test"}""".toByteArray()

        val sigBlock = ManifestSigner.sign(
            manifestJsonBytes = manifestBytes,
            signerKeyPair = keyPair,
            role = "developer",
        )

        assertEquals(1, sigBlock.format)
        assertEquals(1, sigBlock.signatures.size)

        val sig = sigBlock.signatures[0]
        assertEquals("developer", sig.role)
        assertEquals("ed25519", sig.alg)
        assertTrue(sig.keyId.startsWith("sha256:"))
        assertNotNull(sig.key)
        assertNotNull(sig.sig)
    }

    @Test
    fun `developer role embeds public key`() {
        val keyPair = generateKeyPair()
        val manifestBytes = "{}".toByteArray()

        val sigBlock = ManifestSigner.sign(
            manifestJsonBytes = manifestBytes,
            signerKeyPair = keyPair,
            role = "developer",
        )

        assertNotNull(sigBlock.signatures[0].key)
    }

    @Test
    fun `platform role does not embed public key`() {
        val keyPair = generateKeyPair()
        val manifestBytes = "{}".toByteArray()

        val sigBlock = ManifestSigner.sign(
            manifestJsonBytes = manifestBytes,
            signerKeyPair = keyPair,
            role = "platform-release",
        )

        assertEquals(null, sigBlock.signatures[0].key)
    }
}
