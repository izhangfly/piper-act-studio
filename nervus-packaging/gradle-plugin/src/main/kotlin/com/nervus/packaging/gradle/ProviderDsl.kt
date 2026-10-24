package com.nervus.packaging.gradle

import java.io.Serializable

/**
 * Provider contract declaration: the `provider.binpb` + `schemas.binpb` a package must carry when it exports interfaces.
 *
 * ## Why a package that exports interfaces must have it
 *
 * The kernel's `pkgregistry.loadRequiredProviderArtifacts` has a hard rule with no exceptions:
 * if any component in the manifest has `exports`, both artifacts must be provided, or loading is refused with
 * `ErrProviderArtifactsRequired`. The symptom is the package being quarantined during the boot scan,
 * while the package itself logs nothing.
 *
 * There used to be a compatibility bridge just for `nervus.pkgmanagerd`; once the packaging chain could produce both files
 * it was removed entirely. The kernel no longer makes an exception for any package ID.
 *
 * ## Why the artifacts aren't generated here
 *
 * Both byte streams must be **Go's deterministic protobuf encoding**:
 * `provider.binpb` is a `ProviderDescriptor`, `schemas.binpb` an
 * `InterfaceSchemaBundleSet`, and `schema_hash` is the sha256 of the descriptor set,
 * which the kernel compares byte for byte on `RegisterEndpoint`. protobuf-java doesn't guarantee the same bytes as Go,
 * so the JVM side [can't compute] them.
 *
 * nervus-ipc's `registry/providerkit` generates them and commits them to that repository, and they ship inside
 * the `nervus-app-sdk` jar (under `provider/<packageId>/` in the jar).
 * This only pulls them off the classpath, puts them in the package, includes them in the digests and writes them into the manifest.
 *
 * A side benefit is that the artifacts follow the SDK version: when the ipc pin changes, the artifacts change with it, so
 * "the pin was updated but the binpb is stale" can't happen. That mismatch would show up as an interface schema hash mismatch,
 * with an error that doesn't say who is out of date.
 *
 * ## Usage
 *
 * ```kotlin
 * nspkg {
 *     provider { fromClasspath("nervus.permissionui") }
 *     components.app("main") {
 *         exports.register("nervus.interface.permission.ui") { visibility = "public" }
 *     }
 * }
 * ```
 *
 * `fromClasspath` deliberately requires the packageId to be written out rather than defaulting to
 * `nspkg.packageId`: the artifacts are part of the signed contract, and making "which artifacts this package looks for" visible
 * in the build script is worth more than saving an argument when debugging "it was quarantined after install".
 */
class ProviderDsl : Serializable {

    /**
     * The packageId used to fetch artifacts from jars on runtimeClasspath; null means this package exports no interface.
     *
     * The lookup path inside the jar is `provider/<classpathPackageId>/{provider,schemas}.binpb`.
     */
    var classpathPackageId: String? = null
        private set

    /**
     * Declares that the artifacts come from a jar on the classpath (in practice nervus-app-sdk).
     *
     * @param packageId the package the artifacts belong to. It must equal [NspkgExtension.packageId]:
     * the kernel compares the descriptor's `package_id` with the manifest's `package_id`
     * and refuses to load on a mismatch. The plugin also checks this when packaging.
     */
    fun fromClasspath(packageId: String) {
        require(packageId.isNotBlank()) { "provider.fromClasspath(packageId) must not be empty" }
        classpathPackageId = packageId
    }

    /** Whether a Provider contract is declared. */
    internal val isConfigured: Boolean get() = classpathPackageId != null

    internal companion object {
        /**
         * Path of the artifacts inside the package, and the names written in the manifest's `provider` section.
         *
         * Matches the kernel's `pkgregistry.ProviderArtifactsRef` and the names used by each
         * providergen in nervus-system-server: those Go services put both files in the package root.
         */
        const val DESCRIPTOR_ENTRY = "provider.binpb"
        const val SCHEMAS_ENTRY = "schemas.binpb"

        /** Root directory of the artifacts inside the jar; matches the last segment of providerkit's ResourceRoot. */
        const val JAR_RESOURCE_ROOT = "provider"
    }
}
