package com.nervus.packaging.signing

import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.NamedParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object KeyLoader {

    private val ED25519_PUBLIC_KEY_PREFIX = byteArrayOf(
        0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70,
        0x03, 0x21, 0x00,
    )

    fun loadPrivateKey(file: File): PrivateKey {
        val encoded = decodePem(file)
        val keySpec = PKCS8EncodedKeySpec(encoded)
        val kf = KeyFactory.getInstance("Ed25519")
        return kf.generatePrivate(keySpec)
    }

    fun loadKeyPair(file: File): KeyPair {
        val encoded = decodePem(file)

        val kf = KeyFactory.getInstance("Ed25519")
        val privateKey = kf.generatePrivate(PKCS8EncodedKeySpec(encoded))

        val publicKey = extractPublicKeyFromPkcs8(encoded, kf)
            ?: derivePublicKeyFromSeed(encoded, kf)

        return KeyPair(publicKey, privateKey)
    }

    private fun decodePem(file: File): ByteArray {
        val pem = file.readText()
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")
        return Base64.getDecoder().decode(pem)
    }

    private fun extractPublicKeyFromPkcs8(pkcs8: ByteArray, kf: KeyFactory): PublicKey? {
        val pubKeyBytes = findEd25519PublicKeyInDer(pkcs8) ?: return null
        val x509Encoded = ED25519_PUBLIC_KEY_PREFIX + pubKeyBytes
        return kf.generatePublic(X509EncodedKeySpec(x509Encoded))
    }

    private fun derivePublicKeyFromSeed(pkcs8: ByteArray, kf: KeyFactory): PublicKey {
        val seed = extractEd25519Seed(pkcs8)
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        kpg.initialize(NamedParameterSpec("Ed25519"), FixedSeedRandom(seed))
        val kp = kpg.generateKeyPair()
        return kp.public
    }

    private fun extractEd25519Seed(pkcs8: ByteArray): ByteArray {
        var offset = 0
        val (_, outerLen) = readTlv(pkcs8, offset) ?: throw IllegalArgumentException("Invalid PKCS#8")
        // Move to content inside outer SEQUENCE
        offset += 2

        // Skip version INTEGER (tag 02)
        val (_, verLen) = readTlv(pkcs8, offset) ?: throw IllegalArgumentException("Missing version")
        offset += 2 + verLen

        // Skip algorithm identifier SEQUENCE (tag 30)
        val (_, algLen) = readTlv(pkcs8, offset) ?: throw IllegalArgumentException("Missing algorithm")
        offset += 2 + algLen

        // Now at privateKey OCTET STRING (tag 04)
        val (tag, len) = readTlv(pkcs8, offset) ?: throw IllegalArgumentException("Missing private key")
        if (tag != 0x04.toByte()) throw IllegalArgumentException("Expected OCTET STRING for private key, got 0x${tag.toString(16)}")
        val inner = pkcs8.sliceArray(offset + 2 until offset + 2 + len)
        // inner is typically 04 20 <32-byte seed> or just <32-byte seed>
        if (inner.size == 34 && inner[0] == 0x04.toByte() && inner[1] == 0x20.toByte()) {
            return inner.sliceArray(2 until 34)
        }
        if (inner.size == 32) return inner
        throw IllegalArgumentException("Unexpected private key format: ${inner.size} bytes")
    }

    private class FixedSeedRandom(private val fixedSeed: ByteArray) : SecureRandom() {
        private var pos = 0

        override fun nextBytes(bytes: ByteArray) {
            val toCopy = minOf(bytes.size, fixedSeed.size - pos)
            fixedSeed.copyInto(bytes, 0, pos, pos + toCopy)
            pos += toCopy
        }
    }

    private fun findEd25519PublicKeyInDer(der: ByteArray): ByteArray? {
        var offset = 0

        val (tag1, len1) = readTlv(der, offset) ?: return null
        if (tag1 != 0x30.toByte()) return null
        offset += 2 + len1

        if (offset >= der.size) return null

        while (offset < der.size) {
            val (tag, len) = readTlv(der, offset) ?: break
            if (tag == 0xA1.toByte()) {
                val innerStart = offset + 2
                val (innerTag, innerLen) = readTlv(der, innerStart) ?: break
                if (innerTag == 0x03.toByte()) {
                    val bitStringStart = innerStart + 2
                    val unusedBits = der[bitStringStart].toInt() and 0xFF
                    val keyStart = bitStringStart + 1
                    val keyLen = innerLen - 1
                    if (unusedBits == 0 && keyLen == 32) {
                        return der.sliceArray(keyStart until keyStart + keyLen)
                    }
                }
                break
            }
            offset += 2 + len
        }

        return null
    }

    private fun readTlv(data: ByteArray, offset: Int): Pair<Byte, Int>? {
        if (offset + 1 >= data.size) return null
        val tag = data[offset]
        var len = data[offset + 1].toInt() and 0xFF
        if (len == 0x80) {
            return null
        }
        if (len > 0x7F) {
            val numBytes = len and 0x7F
            len = 0
            for (i in 0 until numBytes) {
                if (offset + 2 + i >= data.size) return null
                len = (len shl 8) or (data[offset + 2 + i].toInt() and 0xFF)
            }
        }
        return Pair(tag, len)
    }
}
