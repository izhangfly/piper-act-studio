package com.nervus.packaging.validation

import com.nervus.packaging.model.Component
import com.nervus.packaging.model.ComponentType
import com.nervus.packaging.model.LaunchMode
import com.nervus.packaging.model.ManifestModel
import com.nervus.packaging.model.RuntimeType
import kotlin.test.Test
import kotlin.test.assertTrue

class ManifestValidatorTest {

    private fun validManifest() = ManifestModel(
        packageId = "com.example.test",
        label = "Test",
        version = "1.0.0",
        versionCode = 1UL,
        minNervusApi = 1,
        targetNervusApi = 1,
        supportedAbis = listOf("linux-arm64"),
        components = listOf(
            Component(
                id = "main",
                type = ComponentType.app,
                runtime = RuntimeType.jvm,
                entry = "lib/app.jar",
                launchMode = LaunchMode.manual,
            ),
        ),
        digests = mapOf("lib/app.jar" to "abc123"),
    )

    @Test
    fun `valid manifest passes`() {
        val errors = ManifestValidator.validate(validManifest())
        assertTrue(errors.isEmpty(), "Expected no errors, got: $errors")
    }

    @Test
    fun `empty components rejected`() {
        val m = validManifest().copy(components = emptyList())
        val errors = ManifestValidator.validate(m)
        assertTrue(errors.any { it.contains("at least one component") })
    }

    @Test
    fun `unsupported abi rejected`() {
        val m = validManifest().copy(supportedAbis = listOf("linux-x86"))
        val errors = ManifestValidator.validate(m)
        assertTrue(errors.any { it.contains("unsupported ABI") })
    }

    @Test
    fun `blank label rejected`() {
        val m = validManifest().copy(label = "")
        val errors = ManifestValidator.validate(m)
        assertTrue(errors.any { it.contains("label") })
    }

    @Test
    fun `version code zero rejected`() {
        val m = validManifest().copy(versionCode = 0UL)
        val errors = ManifestValidator.validate(m)
        assertTrue(errors.any { it.contains("version_code") && it.contains("non-zero") })
    }
}
