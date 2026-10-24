package com.nervus.packaging.nspkg

import com.github.luben.zstd.ZstdOutputStream
import com.nervus.packaging.model.SigBlock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream

object NspkgBuilder {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun build(
        outputPath: Path,
        manifestJson: ByteArray,
        manifestSig: SigBlock,
        files: Map<String, Path>,
    ) {
        val outputDir = outputPath.parent
        if (outputDir != null && Files.notExists(outputDir)) {
            Files.createDirectories(outputDir)
        }

        ZstdOutputStream(
            Files.newOutputStream(
                outputPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
        ).use { zstdOut ->
            TarArchiveOutputStream(zstdOut).use { tarOut ->
                tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)

                addTarEntry(tarOut, "manifest.json", manifestJson)

                val sigJson = json.encodeToString(manifestSig).toByteArray()
                addTarEntry(tarOut, "manifest.sig", sigJson)

                for ((relPath, absPath) in files) {
                    val fileBytes = Files.readAllBytes(absPath)
                    addTarEntry(tarOut, relPath, fileBytes)
                }

                tarOut.finish()
            }
        }
    }

    private fun addTarEntry(tarOut: TarArchiveOutputStream, name: String, data: ByteArray) {
        val entry = TarArchiveEntry(name)
        entry.size = data.size.toLong()
        tarOut.putArchiveEntry(entry)
        tarOut.write(data)
        tarOut.closeArchiveEntry()
    }
}
