package com.nervus.packaging.nspkg

import com.nervus.packaging.model.Lineage
import com.nervus.packaging.model.LineageNode
import com.nervus.packaging.model.SigBlock
import com.nervus.packaging.model.Signature
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class NspkgBuilderTest {

    @Test
    fun `build produces valid nspkg file`() {
        val tempDir = Files.createTempDirectory("nspkg-test")
        val outputPath = tempDir.resolve("test-package-1.0.0.nspkg")

        try {
            val contentDir = Files.createTempDirectory("nspkg-content")
            val jarFile = contentDir.resolve("app.jar")
            Files.write(jarFile, byteArrayOf(0x01, 0x02, 0x03))

            val manifestJson = """{"package_id":"com.example.test"}""".toByteArray()
            val sigBlock = SigBlock(
                signatures = listOf(
                    Signature(
                        role = "developer",
                        alg = "ed25519",
                        keyId = "sha256:abc",
                        key = "dGVzdA==",
                        sig = "c2ln",
                    ),
                ),
            )

            val files = mapOf(
                "lib/app.jar" to jarFile,
            )

            NspkgBuilder.build(
                outputPath = outputPath,
                manifestJson = manifestJson,
                manifestSig = sigBlock,
                files = files,
            )

            assertTrue(Files.exists(outputPath))
            assertTrue(Files.size(outputPath) > 0)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
