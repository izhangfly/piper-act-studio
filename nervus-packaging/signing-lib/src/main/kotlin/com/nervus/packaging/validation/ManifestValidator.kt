package com.nervus.packaging.validation

import com.nervus.packaging.model.ManifestModel

object ManifestValidator {

    private val VALID_ABIS = setOf("linux-arm64", "linux-armv7", "linux-x86_64")
    private const val MAX_NERVUS_API = 9999

    fun validate(manifest: ManifestModel): List<String> {
        val errors = mutableListOf<String>()

        if (manifest.schema != 1) {
            errors.add("schema must be 1, got ${manifest.schema}")
        }

        val pkgResult = PackageIdValidator.validate(manifest.packageId)
        if (!pkgResult.isValid) {
            errors.add(pkgResult.errorMessage!!)
        }

        if (manifest.label.isBlank()) {
            errors.add("label must not be blank")
        }

        if (manifest.version.isBlank()) {
            errors.add("version must not be blank")
        }

        if (manifest.versionCode == 0UL) {
            errors.add("version_code must be non-zero")
        }

        if (manifest.minNervusApi < 0 || manifest.minNervusApi > MAX_NERVUS_API) {
            errors.add("min_nervus_api must be between 0 and $MAX_NERVUS_API")
        }

        if (manifest.targetNervusApi < 0 || manifest.targetNervusApi > MAX_NERVUS_API) {
            errors.add("target_nervus_api must be between 0 and $MAX_NERVUS_API")
        }

        for (abi in manifest.supportedAbis) {
            if (abi !in VALID_ABIS) {
                errors.add("unsupported ABI '$abi'; allowed: $VALID_ABIS")
            }
        }

        if (manifest.components.isEmpty()) {
            errors.add("at least one component is required")
        }

        val allIds = mutableSetOf<String>()
        for (component in manifest.components) {
            val compErrors = ComponentValidator.validate(component, allIds)
            errors.addAll(compErrors)
            allIds.add(component.id)

            if (component.entry.isBlank()) {
                errors.add("component '${component.id}' entry must not be blank")
            }

            val entryResult = PathValidator.validate(component.entry)
            if (!entryResult.isValid) {
                errors.add("component '${component.id}': ${entryResult.errorMessage}")
            }

            if (component.nativeLibDir != null) {
                val libResult = PathValidator.validate(component.nativeLibDir)
                if (!libResult.isValid) {
                    errors.add("component '${component.id}': ${libResult.errorMessage}")
                }
            }
        }

        if (manifest.icon != null) {
            val iconResult = PathValidator.validate(manifest.icon)
            if (!iconResult.isValid) {
                errors.add("icon: ${iconResult.errorMessage}")
            }
        }

        for (path in manifest.digests.keys) {
            val digestResult = PathValidator.validate(path)
            if (!digestResult.isValid) {
                errors.add("digests key '$path': ${digestResult.errorMessage}")
            }
        }

        return errors
    }
}
