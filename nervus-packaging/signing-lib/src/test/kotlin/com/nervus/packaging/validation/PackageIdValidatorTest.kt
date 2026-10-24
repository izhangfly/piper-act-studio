package com.nervus.packaging.validation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackageIdValidatorTest {

    @Test
    fun `valid package id`() {
        assertTrue(PackageIdValidator.validate("com.example.app").isValid)
        assertTrue(PackageIdValidator.validate("a").isValid)
        assertTrue(PackageIdValidator.validate("a0").isValid)
        assertTrue(PackageIdValidator.validate("a_b").isValid)
        assertTrue(PackageIdValidator.validate("com.example.my_app_123").isValid)
    }

    @Test
    fun `empty segment rejected`() {
        assertFalse(PackageIdValidator.validate("com..example").isValid)
        assertFalse(PackageIdValidator.validate(".com.example").isValid)
        assertFalse(PackageIdValidator.validate("com.example.").isValid)
    }

    @Test
    fun `too long rejected`() {
        val long = "a".repeat(129)
        assertFalse(PackageIdValidator.validate(long).isValid)
    }

    @Test
    fun `uppercase rejected`() {
        assertFalse(PackageIdValidator.validate("Com.example").isValid)
        assertFalse(PackageIdValidator.validate("com.Example").isValid)
        assertFalse(PackageIdValidator.validate("COM").isValid)
    }

    @Test
    fun `segment with dot rejected`() {
        assertFalse(PackageIdValidator.validate("com.example.app.").isValid)
        assertFalse(PackageIdValidator.validate(".com.example").isValid)
    }

    @Test
    fun `hyphen rejected`() {
        assertFalse(PackageIdValidator.validate("com-example").isValid)
    }

    @Test
    fun `too many segments rejected`() {
        assertFalse(PackageIdValidator.validate("a.b.c.d.e.f.g.h.i").isValid)
    }

    @Test
    fun `max segments allowed`() {
        assertTrue(PackageIdValidator.validate("a.b.c.d.e.f.g.h").isValid)
    }

    @Test
    fun `blank rejected`() {
        assertFalse(PackageIdValidator.validate("").isValid)
        assertFalse(PackageIdValidator.validate("   ").isValid)
    }

    @Test
    fun `segment starting with digit rejected`() {
        assertFalse(PackageIdValidator.validate("1com.example").isValid)
        assertFalse(PackageIdValidator.validate("com.1example").isValid)
    }
}
