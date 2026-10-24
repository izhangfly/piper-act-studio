package com.nervus.packaging.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

class NspkgPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("nspkg", NspkgExtension::class.java)

        // .nspkg: the dynamic-install artifact, developer-signed
        project.tasks.register("nspkg", NspkgTask::class.java) { task ->
            task.extension.set(extension)
            task.group = GROUP
            task.description = "Build .nspkg package (dynamic install)"
            project.tasks.findByName("jar")?.let { task.dependsOn(it) }
        }

        // Image tree: the artifact for built-in system packages, platform-signed.
        // Both tasks share the same DSL and PackageLayout; only how they write to disk and the signing role differ
        project.tasks.register("nspkgImageTree", NspkgImageTreeTask::class.java) { task ->
            task.extension.set(extension)
            task.group = GROUP
            task.description = "Build system image tree (/usr/lib/nervus/system-packages)"
            project.tasks.findByName("jar")?.let { task.dependsOn(it) }
        }
    }

    private companion object {
        const val GROUP = "nervus packaging"
    }
}
