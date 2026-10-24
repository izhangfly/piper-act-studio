package com.nervus.packaging.gradle

import com.nervus.packaging.signing.KeyLoader
import com.nervus.packaging.signing.ManifestSigner
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Produces a **system image tree**: a directory that can be copied straight into `/usr/lib/nervus/system-packages/`.
 *
 * ```text
 * <outputDir>/<package_id>/
 * ├── manifest.json     ← the exact bytes that are signed
 * ├── manifest.sig      ← Ed25519 signature by a platform role
 * └── lib/                ← payload jars, all covered by manifest.digests
 * ```
 *
 * ## Relationship to [NspkgTask]
 *
 * The package layout and manifest are **one and the same** ([PackageLayout]); there are only two differences:
 *
 * 1. **How it's written**: a plain directory here, no zstd+tar. The system image area is mounted read-only
 *    and has no unpack step; the kernel's boot scan globs and reads each package directory's manifest.json directly.
 * 2. **Signing role**: image trees only accept platform roles (default `platform-systemapp`; core system
 *    services use `platform-release`), while `.nspkg` uses `developer`.
 *
 * ## Why it can't be installed with nervusctl install
 *
 * `pkgregistry.Install` only accepts `SourceDynamicInstall`; a system package sent that way is refused
 * with `ErrSystemPackageImmutable`. System packages arrive by image flash or OTA, deliberately:
 * the admission rules on that path (mandatory developer signature, OEM countersign) are for third-party packages; system packages follow different ones.
 *
 * The output must have the same shape as `nervus-system-server/scripts/build-image-tree.sh` (the Go side of the same job);
 * both sides' manifests are held to the kernel's `pkgregistry.ParseManifest`.
 */
abstract class NspkgImageTreeTask : DefaultTask() {

    @get:Internal
    abstract val extension: Property<NspkgExtension>

    @OutputDirectory
    val outputDir: File = project.layout.buildDirectory.get().asFile.resolve("outputs/image-tree")

    /**
     * prettyPrint = false: the **raw bytes** of manifest.json are what's signed,
     * and what the kernel reads when it checks the signature. Formatting doesn't affect correctness, but it must match
     * [NspkgTask], or one DSL would produce two byte layouts and neither the digest nor the signature would match.
     */
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        prettyPrint = false
    }

    /** Serializer for manifest.sig. Configured the same as the one inside NspkgBuilder */
    private val sigJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    init {
        // This task is [never] considered up-to-date.
        //
        // Its input is the whole nspkg DSL (package_id, permissions, components, launch_mode...),
        // a complex Serializable object tree that can't be declared item by item with @Input. Declaring only
        // @OutputDirectory means Gradle skips the task once the output directory exists:
        // change the DSL, rebuild, and the manifest still has the old content, with BUILD SUCCESSFUL.
        // That really happened: launch_mode changed from on-demand to manual and the permission swapped for
        // perm.system.launch, the build "succeeded", and the manifest didn't change a character.
        //
        // Packaging isn't a hot path (tens of seconds a time), so trading incrementality for correctness is worth it.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun build() {
        val ext = extension.get()
        val packageId = ext.packageId.get()
        val thinJarDir = project.layout.buildDirectory.get().asFile.resolve("tmp/thin-jars-image")

        val fileMap = PackageLayout.collectFiles(project, ext, thinJarDir)
        val manifestJsonBytes = json.encodeToString(PackageLayout.buildManifest(ext, fileMap)).toByteArray()

        val keyFile = ext.signing.keyFile
            ?: throw GradleException("signing.keyFile must be configured")

        // Role: defaults to platform-systemapp (system apps shipped with the image).
        //
        // The line between it and platform-release is [how far the permissions reach], not location:
        //   platform-release   kernel + core system services; can get permissions limited by RequireSignerRole,
        //                      such as perm.safety.rearm / perm.authority.reboot
        //   platform-systemapp system apps; Platform trust, but not the permissions above
        // Both are installed in /usr/lib/nervus/system-packages/; deploy/README.md §3.3 has the comparison table.
        //
        // developer is refused: image trees are [only] for system packages, and a developer signature would be downgraded
        // to Ordinary trust by the kernel, with the symptom far from the cause (an endpoint failing to register or a permission
        // denied at run time). Better to fail at build time
        val role = ext.signing.role.takeIf { it.isNotBlank() && it != DEFAULT_DSL_ROLE }
            ?: DEFAULT_IMAGE_TREE_ROLE
        if (role !in PLATFORM_ROLES) {
            throw GradleException(
                "signing.role='$role' can't be used for a system image tree; image trees only accept $PLATFORM_ROLES. " +
                    "For third-party apps use the nspkg task (dynamic install)."
            )
        }

        val sigBlock = ManifestSigner.sign(
            manifestJsonBytes = manifestJsonBytes,
            signerKeyPair = KeyLoader.loadKeyPair(keyFile),
            role = role,
        )

        // The directory name must equal package_id: the kernel identifies packages by manifest content and the directory is just a container,
        // but a mismatch sends people debugging to the wrong package
        val pkgDir = outputDir.resolve(packageId)
        if (pkgDir.exists()) pkgDir.deleteRecursively()
        pkgDir.mkdirs()

        for ((relPath, source) in fileMap) {
            val dest = pkgDir.resolve(relPath)
            dest.parentFile?.mkdirs()
            Files.copy(source, dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        pkgDir.resolve(MANIFEST_NAME).writeBytes(manifestJsonBytes)
        // ManifestSigner produces a SigBlock model, written to disk as JSON.
        // The serializer config must match NspkgBuilder's (encodeDefaults = true): the kernel only parses
        // manifest.sig as JSON and doesn't compare bytes, but two artifact paths producing different
        // byte layouts would make "the two forms of the same package" hard to compare when debugging
        pkgDir.resolve(SIGNATURE_NAME).writeBytes(sigJson.encodeToString(sigBlock).toByteArray())

        logger.lifecycle("image-tree: ${pkgDir.absolutePath}")
        logger.lifecycle("  abis=${ext.supportedAbis.get()}  files=${fileMap.size}  role=$role")
        logger.lifecycle("  install: cp -r ${pkgDir.absolutePath} <image>/usr/lib/nervus/system-packages/")
    }

    private companion object {
        /** Matches the SignerRole constants in the kernel's sigblock.go */
        const val ROLE_PLATFORM_RELEASE = "platform-release"
        const val ROLE_PLATFORM_SYSTEMAPP = "platform-systemapp"

        /** The default of SigningDsl.role. Equal to it means the caller didn't set one */
        const val DEFAULT_DSL_ROLE = "developer"

        /** The default role for image trees: system app */
        const val DEFAULT_IMAGE_TREE_ROLE = ROLE_PLATFORM_SYSTEMAPP

        val PLATFORM_ROLES = setOf(ROLE_PLATFORM_RELEASE, ROLE_PLATFORM_SYSTEMAPP)

        /** Matches the kernel pkgregistry's ManifestFileName / SignatureFileName */
        const val MANIFEST_NAME = "manifest.json"
        const val SIGNATURE_NAME = "manifest.sig"
    }
}
