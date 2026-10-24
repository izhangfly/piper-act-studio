package com.nervus.packaging.digest

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DigestCalculatorTest {

    @Test
    fun `calculate single file hash`() {
        val tempFile = Files.createTempFile("test", ".bin")
        try {
            Files.write(tempFile, byteArrayOf(0x48, 0x65, 0x6C, 0x6C, 0x6F)) // "Hello"
            val hash = DigestCalculator.calculateSingle(tempFile)
            assertEquals(64, hash.length)
            assertTrue(hash.matches(Regex("^[a-f0-9]{64}$")))
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    @Test
    fun `calculate multiple files`() {
        val dir = Files.createTempDirectory("digest-test")
        try {
            val file1 = dir.resolve("a.txt")
            val file2 = dir.resolve("b.txt")
            Files.write(file1, byteArrayOf(0x01, 0x02, 0x03))
            Files.write(file2, byteArrayOf(0x04, 0x05, 0x06))

            val files = mapOf(
                "a.txt" to file1,
                "b.txt" to file2,
            )

            val digests = DigestCalculator.calculate(files)
            assertEquals(2, digests.size)
            assertTrue(digests.containsKey("a.txt"))
            assertTrue(digests.containsKey("b.txt"))
            assertEquals(64, digests["a.txt"]?.length)
            assertEquals(64, digests["b.txt"]?.length)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `consistent hash for same content`() {
        val tempFile1 = Files.createTempFile("test1", ".bin")
        val tempFile2 = Files.createTempFile("test2", ".bin")
        try {
            val content = "Hello World!".toByteArray()
            Files.write(tempFile1, content)
            Files.write(tempFile2, content)

            val hash1 = DigestCalculator.calculateSingle(tempFile1)
            val hash2 = DigestCalculator.calculateSingle(tempFile2)
            assertEquals(hash1, hash2)
        } finally {
            Files.deleteIfExists(tempFile1)
            Files.deleteIfExists(tempFile2)
        }
    }
}
