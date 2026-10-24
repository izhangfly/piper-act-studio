package com.nervus.packaging.signing

import kotlin.test.Test
import kotlin.test.assertEquals
import java.security.KeyPair
import java.security.KeyPairGenerator

class CountersignerTest {

    private fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        return kpg.generateKeyPair()
    }

    @Test
    fun `add countersign appends signature`() {
        val devKey = generateKeyPair()
        val oemKey = generateKeyPair()
        val manifestBytes = """{"package_id":"com.example.test"}""".toByteArray()

        val sigBlock = ManifestSigner.sign(
            manifestJsonBytes = manifestBytes,
            signerKeyPair = devKey,
            role = "developer",
        )

        assertEquals(1, sigBlock.signatures.size)

        val countersigned = Countersigner.addCountersign(
            sigBlock = sigBlock,
            manifestJsonBytes = manifestBytes,
            countersignKeyPair = oemKey,
            role = "oem-app",
        )

        assertEquals(2, countersigned.signatures.size)
        assertEquals("developer", countersigned.signatures[0].role)
        assertEquals("oem-app", countersigned.signatures[1].role)
    }
}
