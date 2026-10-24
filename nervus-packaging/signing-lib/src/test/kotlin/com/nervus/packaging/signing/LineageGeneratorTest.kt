package com.nervus.packaging.signing

import com.nervus.packaging.model.Lineage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.security.KeyPair
import java.security.KeyPairGenerator

class LineageGeneratorTest {

    private fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        return kpg.generateKeyPair()
    }

    @Test
    fun `initial node has no signedByPrev`() {
        val keyPair = generateKeyPair()

        val node = LineageGenerator.generate(
            previousNode = null,
            previousKeyPair = null,
            newNodeKeyPair = keyPair,
        )

        assertNull(node.signedByPrev)
        assertTrue(node.keyId.startsWith("sha256:"))
        assertNotNull(node.key)
    }

    @Test
    fun `subsequent node signs with previous key`() {
        val prevKey = generateKeyPair()
        val newKey = generateKeyPair()

        val prevNode = LineageGenerator.generate(
            previousNode = null,
            previousKeyPair = null,
            newNodeKeyPair = prevKey,
        )

        val newNode = LineageGenerator.generate(
            previousNode = prevNode,
            previousKeyPair = prevKey,
            newNodeKeyPair = newKey,
        )

        assertNotNull(newNode.signedByPrev)
        assertEquals("sha256:" + sha256Hex(newKey.public.encoded), newNode.keyId)
    }

    @Test
    fun `build lineage with multiple nodes`() {
        val key1 = generateKeyPair()
        val key2 = generateKeyPair()
        val key3 = generateKeyPair()

        val node1 = LineageGenerator.generate(null, null, key1)
        val node2 = LineageGenerator.generate(node1, key1, key2)
        val node3 = LineageGenerator.generate(node2, key2, key3)

        val lineage = LineageGenerator.buildLineage(listOf(node1, node2, node3))
        assertEquals(3, lineage.nodes.size)
    }

    @Test
    fun `verify lineage integrity passes`() {
        val key1 = generateKeyPair()
        val key2 = generateKeyPair()
        val key3 = generateKeyPair()

        val node1 = LineageGenerator.generate(null, null, key1)
        val node2 = LineageGenerator.generate(node1, key1, key2)
        val node3 = LineageGenerator.generate(node2, key2, key3)

        val lineage = Lineage(nodes = listOf(node1, node2, node3))
        LineageGenerator.verifyLineageIntegrity(lineage, key3.public)
    }

    @Test
    fun `empty lineage rejected`() {
        var caught = false
        try {
            LineageGenerator.buildLineage(emptyList())
        } catch (e: IllegalArgumentException) {
            caught = true
        }
        assertTrue(caught)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
