package com.nervus.packaging.digest

import java.io.InputStream
import java.nio.file.Path
import java.security.MessageDigest

object DigestCalculator {

    private const val BUFFER_SIZE = 8192

    fun calculate(files: Map<String, Path>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for ((relPath, absPath) in files) {
            val hash = sha256Hex(absPath)
            result[relPath] = hash
        }
        return result
    }

    fun calculateSingle(file: Path): String {
        return sha256Hex(file)
    }

    private fun sha256Hex(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        path.toFile().inputStream().use { stream: InputStream ->
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
