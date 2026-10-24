package com.nervus.packaging.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class NspkgPluginFunctionalTest {

    @Test
    fun `plugin registers nspkg task`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply(NspkgPlugin::class.java)

        val task = project.tasks.findByName("nspkg")
        assertNotNull(task, "nspkg task should be registered")
    }
}