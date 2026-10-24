package com.nervus.packaging.gradle

import com.nervus.packaging.digest.DigestCalculator
import com.nervus.packaging.model.Component
import com.nervus.packaging.model.ComponentType
import com.nervus.packaging.model.Criticality
import com.nervus.packaging.model.Export
import com.nervus.packaging.model.LaunchMode
import com.nervus.packaging.model.ManifestModel
import com.nervus.packaging.model.ProviderRef
import com.nervus.packaging.model.ResourceLimits
import com.nervus.packaging.model.RuntimeDeps
import com.nervus.packaging.model.RuntimeType
import com.nervus.packaging.model.UsesFeature
import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest as JarManifest

/**
 * PackageLayout is the single implementation of "which files go in a package, and what the manifest looks like".
 *
 * Both artifact forms use it:
 *   - [NspkgTask]          → .nspkg (zstd+tar, dynamic install, developer-signed)
 *   - [NspkgImageTreeTask] → directory tree (system image area, platform-signed)
 *
 * They differ [only in how they're written to disk]. With two copies of the layout and manifest, the paths would drift sooner or later,
 * and the drift would show up as the hardest kind of problem: "works installed dynamically, won't start from the image".
 */
internal object PackageLayout {

    /**
     * Collects every file in the package; returns path inside the package -> absolute local path.
     *
     * ## Why runtimeClasspath must be included
     *
     * It used to collect only the `jar` task's output (the project's own single jar), so any app with dependencies
     * was packaged unable to run: Compose Desktop's runtime alone is hundreds of jars (skiko,
     * kotlin-stdlib, coroutines, protobuf, the SDK...), none of them were in the package,
     * and start-up failed with NoClassDefFoundError.
     *
     * This takes the `runtimeClasspath` configuration, which is exactly the authoritative answer to "which jars
     * does this app need to run", worked out by Gradle's dependency resolution, so we don't have to guess.
     */
    fun collectFiles(
        project: Project,
        ext: NspkgExtension,
        thinJarDir: File,
    ): Map<String, Path> {
        val map = LinkedHashMap<String, Path>()

        // The project's own jar + every runtime dependency jar.
        // A LinkedHashMap keeps the order: Class-Path order is class-loading order, and reproducible builds need it stable.
        val runtimeJars = LinkedHashMap<String, File>()
        for (jar in project.files(project.tasks.named("jar")).files) {
            runtimeJars[jar.name] = jar
        }
        val runtimeClasspath = project.configurations.findByName("runtimeClasspath")
        if (runtimeClasspath != null) {
            for (dep in runtimeClasspath.files.sortedBy { it.name }) {
                if (!dep.name.endsWith(".jar")) continue
                // Jars with the same name (same artifact name from different groups) would overwrite each other. Prefix the group to disambiguate
                // instead of silently dropping one, whose classes would only show up missing at run time as NoClassDefFoundError
                var name = dep.name
                if (runtimeJars.containsKey(name) && runtimeJars[name] != dep) {
                    name = "${dep.parentFile?.parentFile?.name ?: "dep"}-${dep.name}"
                }
                runtimeJars.putIfAbsent(name, dep)
            }
        }
        for ((name, file) in runtimeJars) {
            map["lib/$name"] = file.toPath()
        }

        // One thin jar per jvm component: just a MANIFEST naming the Main-Class and Class-Path.
        // A package can have several jvm components (such as the launcher's desktop and launcherd),
        // which share the same dependency jars and only differ in their entry class
        val classPathEntries = runtimeJars.keys.toList()
        for (spec in ext.components.components) {
            if (spec.runtime.lowercase() != "jvm") continue
            val mainClass = spec.mainClass
                ?: throw GradleException("Component '${spec.id}' with runtime 'jvm' must specify mainClass")
            val thinJar = thinJarDir.resolve("${spec.id}.jar")
            writeThinJar(thinJar, mainClass, classPathEntries)
            map["lib/${spec.id}.jar"] = thinJar.toPath()
        }

        for ((abi, file) in collectNativeLibs(project)) {
            map["bin/$abi/${file.name}"] = file.toPath()
        }

        val resourceRoot = project.file("src/main/resources")
        if (resourceRoot.exists()) {
            for (res in project.files(resourceRoot).asFileTree.files) {
                val rel = resourceRoot.toPath().relativize(res.toPath()).toString().replace(File.separatorChar, '/')
                map["resources/$rel"] = res.toPath()
            }
        }

        val iconPath = ext.icon.orNull
        if (iconPath != null) {
            val iconFile = project.file(iconPath)
            if (!iconFile.exists()) {
                throw GradleException("icon '$iconPath' not found at ${iconFile.absolutePath}")
            }
            map[iconPath] = iconFile.toPath()
        }

        // The Provider contract goes in [last]: it must be in fileMap to be covered by DigestCalculator,
        // and the kernel refuses to load a package whose provider isn't covered by a digest
        extractProviderArtifacts(project, ext)?.let { (descriptor, schemas) ->
            map[ProviderDsl.DESCRIPTOR_ENTRY] = descriptor
            map[ProviderDsl.SCHEMAS_ENTRY] = schemas
        }

        return map
    }

    /**
     * Extracts the Provider contract artifacts from jars on runtimeClasspath into the build directory.
     *
     * Returns two local paths (descriptor, schemas); null when the package declares no provider.
     *
     * ## Why exports and provider are checked for consistency here
     *
     * The kernel refuses both cases, but the errors are far from the cause:
     *   exports without provider → the package is quarantined during the boot scan (ErrProviderArtifactsRequired)
     *   provider without exports → the Catalog build fails, and every package on the machine is affected
     * Stopping it at build time lets the error point straight at the line missing from the build script.
     */
    private fun extractProviderArtifacts(
        project: Project,
        ext: NspkgExtension,
    ): Pair<Path, Path>? {
        val declaresExports = ext.components.components.any { it.exports.exports.isNotEmpty() }
        val providerPackageId = ext.provider.classpathPackageId

        if (providerPackageId == null) {
            if (declaresExports) {
                throw GradleException(
                    "A component declares exports but the nspkg has no provider section; the kernel will refuse to load it with " +
                        "ErrProviderArtifactsRequired (the package is quarantined during the boot scan). " +
                        "Add provider { fromClasspath(\"${ext.packageId.get()}\") }."
                )
            }
            return null
        }
        if (!declaresExports) {
            throw GradleException(
                "The nspkg declares a provider section but no component exports an interface; " +
                    "the kernel's Catalog build closes both ways, so every interface in the descriptor must be exported by some " +
                    "component, or the whole Catalog build fails."
            )
        }

        // The package_id in the descriptor must equal the manifest's package_id; the kernel compares them.
        // Catching it here beats reading "descriptor package_id mismatch" at load time
        val manifestPackageId = ext.packageId.get()
        if (providerPackageId != manifestPackageId) {
            throw GradleException(
                "provider.fromClasspath(\"$providerPackageId\") doesn't match packageId " +
                    "\"$manifestPackageId\"; the kernel compares the descriptor's package_id " +
                    "with the manifest's package_id and refuses to load on a mismatch."
            )
        }

        val outDir = project.layout.buildDirectory.get().asFile
            .resolve("tmp/nspkg-provider/$providerPackageId")
        outDir.mkdirs()

        val descriptorOut = outDir.resolve(ProviderDsl.DESCRIPTOR_ENTRY)
        val schemasOut = outDir.resolve(ProviderDsl.SCHEMAS_ENTRY)

        val root = "${ProviderDsl.JAR_RESOURCE_ROOT}/$providerPackageId"
        extractFromClasspath(project, "$root/${ProviderDsl.DESCRIPTOR_ENTRY}", descriptorOut)
        extractFromClasspath(project, "$root/${ProviderDsl.SCHEMAS_ENTRY}", schemasOut)

        return descriptorOut.toPath() to schemasOut.toPath()
    }

    /**
     * Looks for [entryPath] in every jar on runtimeClasspath and writes it to [destination] when found.
     *
     * Not finding it is a failure, [not downgraded to a warning]: a missing artifact means the package gets quarantined after install,
     * and then the error only appears in nervud's start-up log while the package itself says nothing.
     */
    private fun extractFromClasspath(project: Project, entryPath: String, destination: File) {
        val runtimeClasspath = project.configurations.findByName("runtimeClasspath")
            ?: throw GradleException("the runtimeClasspath configuration doesn't exist, so the Provider contract artifacts can't be fetched")

        for (dep in runtimeClasspath.files.sortedBy { it.name }) {
            if (!dep.name.endsWith(".jar")) continue
            java.util.zip.ZipFile(dep).use { zip ->
                val entry = zip.getEntry(entryPath) ?: return@use
                destination.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    destination.outputStream().buffered().use { output -> input.copyTo(output) }
                }
            }
            if (destination.exists()) {
                project.logger.info("provider artifact $entryPath <- ${dep.name}")
                return
            }
        }
        throw GradleException(
            "Provider contract artifact '$entryPath' not found on the classpath. " +
                "nervus-ipc's registry/providerkit generates it and it ships inside the nervus-app-sdk jar; " +
                "check that this SDK version includes the artifacts for this package."
        )
    }

    /**
     * Writes a thin jar: no classes, only a MANIFEST pointing at the Main-Class and Class-Path.
     *
     * ## Why java.util.jar.Manifest rather than a hand-written string
     *
     * MANIFEST.MF has a hard format rule: **at most 72 bytes per line; longer lines must wrap, and continuation lines
     * start with a space** (JAR specification). Compose Desktop's Class-Path is hundreds of jar names
     * joined together, thousands of bytes long, so a hand-written string is bound to exceed it, and the result isn't an error:
     * **the JVM silently truncates the Class-Path**, only the first few jars load, and you get a NoClassDefFoundError
     * unrelated to the real cause.
     *
     * Manifest.write() handles the wrapping itself; leave it to that and it can't go wrong.
     */
    private fun writeThinJar(outputFile: File, mainClass: String, classPath: List<String>) {
        outputFile.parentFile?.mkdirs()
        val manifest = JarManifest()
        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        manifest.mainAttributes[Attributes.Name.MAIN_CLASS] = mainClass
        // Class-Path is relative to this jar's directory, so sibling file names are enough (all under lib/)
        manifest.mainAttributes[Attributes.Name.CLASS_PATH] = classPath.joinToString(" ")
        JarOutputStream(outputFile.outputStream().buffered()).use { jos ->
            jos.putNextEntry(JarEntry("META-INF/MANIFEST.MF"))
            manifest.write(jos)
            jos.closeEntry()
        }
    }

    private fun collectNativeLibs(project: Project): Map<String, File> {
        val result = mutableMapOf<String, File>()
        val nativeDir = project.file("src/main/native")
        if (!nativeDir.exists()) return result
        nativeDir.listFiles()?.forEach { abiDir ->
            if (!abiDir.isDirectory) return@forEach
            abiDir.listFiles()?.forEach { file ->
                if (file.isFile) result[abiDir.name] = file
            }
        }
        return result
    }

    /** Builds the manifest.json model from the DSL and the file list. Digests are computed fresh by [DigestCalculator]. */
    fun buildManifest(ext: NspkgExtension, fileMap: Map<String, Path>): ManifestModel {
        val components = ext.components.components.map { spec ->
            val isJvm = spec.runtime.lowercase() == "jvm"
            Component(
                id = spec.id,
                type = when (spec) {
                    is ComponentDsl.AppComponentSpec -> ComponentType.app
                    is ComponentDsl.ServiceComponentSpec -> ComponentType.service
                    else -> throw GradleException("Unknown component type for '${spec.id}'")
                },
                runtime = when (spec.runtime.lowercase()) {
                    "native" -> RuntimeType.native
                    "jvm" -> RuntimeType.jvm
                    else -> throw GradleException("Unknown runtime '${spec.runtime}' for component '${spec.id}'")
                },
                // A jvm component's entry is always its own thin jar
                entry = if (isJvm) "lib/${spec.id}.jar" else spec.entry,
                nativeLibDir = spec.nativeLibDir,
                launchMode = when (spec.launchMode) {
                    "always-on" -> LaunchMode.always_on
                    "on-demand" -> LaunchMode.on_demand
                    "manual" -> LaunchMode.manual
                    else -> throw GradleException("Unknown launch_mode '${spec.launchMode}' for component '${spec.id}'")
                },
                criticality = spec.criticality?.let { c ->
                    when (c.lowercase()) {
                        "optional" -> Criticality.optional
                        "required" -> Criticality.required
                        "vital" -> Criticality.vital
                        else -> throw GradleException("Unknown criticality '$c' for component '${spec.id}'")
                    }
                },
                disableable = spec.disableable,
                exports = spec.exports.exports.takeIf { it.isNotEmpty() }?.map { e ->
                    Export(`interface` = e.interfaceName, visibility = e.visibility)
                },
                interfaces = spec.interfaces,
                idleTimeoutSec = spec.idleTimeoutSec,
                limits = spec.limits.run {
                    if (memoryMaxMb != null || cpuQuotaPercent != null || tasksMax != null) {
                        // Named arguments: ResourceLimits' three fields are all Int?, so positional arguments in the wrong
                        // order compile without a word, and the memory limit would be taken as the CPU quota
                        ResourceLimits(
                            memoryMaxMb = memoryMaxMb,
                            cpuQuotaPercent = cpuQuotaPercent,
                            tasksMax = tasksMax,
                        )
                    } else null
                },
            )
        }

        return ManifestModel(
            packageId = ext.packageId.get(),
            label = ext.label.get(),
            labels = ext.labels.orNull?.takeIf { it.isNotEmpty() },
            icon = ext.icon.orNull,
            version = ext.version.get(),
            versionCode = ext.versionCode.get().toULong(),
            minNervusApi = ext.minNervusApi.get(),
            targetNervusApi = ext.targetNervusApi.get(),
            supportedAbis = ext.supportedAbis.get(),
            runtimeDeps = ext.runtimeDeps.minJavaRelease?.let { RuntimeDeps(minJavaRelease = it) },
            permissions = ext.permissions.orNull?.toList(),
            usesFeatures = ext.usesFeatures.features.takeIf { it.isNotEmpty() }?.map { f ->
                UsesFeature(id = f.id, required = f.required)
            },
            components = components,
            // The provider section and those two fileMap entries must appear together or not at all:
            // a section without the files means the kernel can't read the artifacts; files without the section mean it doesn't know to read them.
            // Both depend on whether ext.provider is configured, so the same test is used here
            provider = if (ext.provider.isConfigured) {
                ProviderRef(
                    descriptor = ProviderDsl.DESCRIPTOR_ENTRY,
                    schemas = ProviderDsl.SCHEMAS_ENTRY,
                )
            } else null,
            digests = DigestCalculator.calculate(fileMap),
        )
    }
}
