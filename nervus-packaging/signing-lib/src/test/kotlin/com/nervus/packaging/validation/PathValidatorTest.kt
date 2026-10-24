package com.nervus.packaging.validation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PathValidatorTest {

    @Test
    fun `valid relative paths`() {
        assertTrue(PathValidator.validate("lib/app.jar").isValid)
        assertTrue(PathValidator.validate("resources/icon.png").isValid)
        assertTrue(PathValidator.validate("a").isValid)
        assertTrue(PathValidator.validate("bin/arm64/exec").isValid)
    }

    @Test
    fun `absolute path rejected`() {
        assertFalse(PathValidator.validate("/etc/passwd").isValid)
        assertFalse(PathValidator.validate("/bin/sh").isValid)
    }

    @Test
    fun `path traversal rejected`() {
        assertFalse(PathValidator.validate("../etc/passwd").isValid)
        assertFalse(PathValidator.validate("lib/../../etc").isValid)
        assertFalse(PathValidator.validate("..").isValid)
    }

    @Test
    fun `null byte rejected`() {
        assertFalse(PathValidator.validate("lib/\u0000app.jar").isValid)
    }

    @Test
    fun `blank rejected`() {
        assertFalse(PathValidator.validate("").isValid)
        assertFalse(PathValidator.validate("   ").isValid)
    }
}
