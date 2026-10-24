package com.nervus.packaging.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ManifestModel(
    val schema: Int = 1,
    @SerialName("package_id")
    val packageId: String,
    val label: String,
    val labels: Map<String, String>? = null,
    val icon: String? = null,
    val version: String,
    @SerialName("version_code")
    val versionCode: ULong,
    @SerialName("min_nervus_api")
    val minNervusApi: Int,
    @SerialName("target_nervus_api")
    val targetNervusApi: Int,
    @SerialName("supported_abis")
    val supportedAbis: List<String>,
    @SerialName("runtime_deps")
    val runtimeDeps: RuntimeDeps? = null,
    val permissions: List<String>? = null,
    @SerialName("uses_features")
    val usesFeatures: List<UsesFeature>? = null,
    val components: List<Component>,
    /**
     * Data-driven reference to the Provider contract; null when the package exports no interface.
     *
     * The kernel's `pkgregistry.loadRequiredProviderArtifacts` requires it: if any component in the manifest
     * has `exports`, this section must exist, or loading is refused with
     * `ErrProviderArtifactsRequired` (the package is quarantined during the boot scan).
     *
     * Both files must be covered by [digests]: they decide what goes into the kernel's Catalog,
     * and anything not covered by a digest could be swapped outside the signature.
     */
    val provider: ProviderRef? = null,
    val digests: Map<String, String>,
)

/**
 * The `provider` section: paths inside the package to the two deterministic protobuf artifacts.
 *
 * The field names must be `descriptor` and `schemas`: the kernel parses manifest.json with `DisallowUnknownFields`,
 * so one extra or renamed field makes the whole manifest fail to parse.
 *
 * The two must not be the same file; the kernel and sysmanifest both refuse that.
 */
@Serializable
data class ProviderRef(
    val descriptor: String,
    val schemas: String,
)
