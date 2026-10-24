package com.nervus.packaging.gradle

import com.nervus.packaging.nspkg.NspkgBuilder
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

/**
 * Produces a `.nspkg` (zstd + tar) for dynamic install (App Store or `nervusctl install`).
 *
 * The package layout and manifest all come from [PackageLayout], the same implementation the image-tree
 * artifact ([NspkgImageTreeTask]) uses, so the two paths can't diverge.
 */
abstract class NspkgTask : DefaultTask() {

    @get:Internal
    abstract val extension: Property<NspkgExtension>

    @OutputDirectory
    val outputDir: java.io.File = project.layout.buildDirectory.get().asFile.resolve("outputs/nspkg")

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        prettyPrint = false
    }

    init {
        // Never up-to-date, for the same reason as NspkgImageTreeTask: with only @OutputDirectory declared,
        // Gradle skips the task once build/outputs/nspkg exists. The app's code changes, the jar is rebuilt,
        // and the .nspkg still carries the old jar with BUILD SUCCESSFUL. That happened here: a new main()
        // compiled into the jar but never reached the installed package.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun build() {
        val ext = extension.get()
        val thinJarDir = project.layout.buildDirectory.get().asFile.resolve("tmp/thin-jars")

        val fileMap = PackageLayout.collectFiles(project, ext, thinJarDir)
        val manifestJsonBytes = json.encodeToString(PackageLayout.buildManifest(ext, fileMap)).toByteArray()

        val keyFile = ext.signing.keyFile
            ?: throw GradleException("signing.keyFile must be configured")
        val sigBlock = ManifestSigner.sign(
            manifestJsonBytes = manifestJsonBytes,
            signerKeyPair = KeyLoader.loadKeyPair(keyFile),
            role = ext.signing.role,
        )

        outputDir.mkdirs()
        val outputFile = outputDir.resolve("${ext.packageId.get()}-${ext.version.get()}.nspkg")
        NspkgBuilder.build(
            outputPath = outputFile.toPath(),
            manifestJson = manifestJsonBytes,
            manifestSig = sigBlock,
            files = fileMap,
        )

        logger.lifecycle("nspkg: ${outputFile.absolutePath}")
    }
}
