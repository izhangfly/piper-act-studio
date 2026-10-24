package com.nervus.packaging.signing

import com.nervus.packaging.model.Lineage
import com.nervus.packaging.model.LineageNode
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature as JSignature
import java.util.Base64

object LineageGenerator {

    private const val NERVUS_LINEAGE_PREFIX = "nervus-lineage-v1\u0000"
    private const val MAX_NODES = 16

    fun generate(
        previousNode: LineageNode?,
        previousKeyPair: KeyPair?,
        newNodeKeyPair: KeyPair,
    ): LineageNode {
        val pubEncoded = newNodeKeyPair.public.encoded
        val sha256 = MessageDigest.getInstance("SHA-256")
        val keyId = "sha256:" + sha256.digest(pubEncoded).joinToString("") { "%02x".format(it) }
        val key = Base64.getEncoder().encodeToString(pubEncoded)

        if (previousNode == null) {
            return LineageNode(keyId = keyId, key = key, signedByPrev = null)
        }

        require(previousKeyPair != null) {
            "previousKeyPair must be provided when previousNode is not null"
        }

        val toSign = NERVUS_LINEAGE_PREFIX.toByteArray() +
            keyId.toByteArray() + key.toByteArray()

        val sig = JSignature.getInstance("Ed25519").apply {
            initSign(previousKeyPair.private)
            update(toSign)
        }
        val signedByPrev = Base64.getEncoder().encodeToString(sig.sign())

        return LineageNode(keyId = keyId, key = key, signedByPrev = signedByPrev)
    }

    fun buildLineage(nodes: List<LineageNode>): Lineage {
        require(nodes.isNotEmpty()) { "lineage must have at least one node" }
        require(nodes.size <= MAX_NODES) { "lineage node count ${nodes.size} exceeds max $MAX_NODES" }
        return Lineage(nodes = nodes)
    }

    fun verifyLineageIntegrity(lineage: Lineage, currentPublicKey: PublicKey?) {
        val nodes = lineage.nodes
        require(nodes.isNotEmpty()) { "lineage must have at least one node" }

        for (i in 1 until nodes.size) {
            val prev = nodes[i - 1]
            val cur = nodes[i]

            val signedByPrev = cur.signedByPrev
            require(signedByPrev != null) { "node $i missing signedByPrev" }

            val prevPubBytes = Base64.getDecoder().decode(prev.key)
            val kf = java.security.KeyFactory.getInstance("Ed25519")
            val prevPub = kf.generatePublic(
                java.security.spec.X509EncodedKeySpec(prevPubBytes)
            )

            val toVerify = NERVUS_LINEAGE_PREFIX.toByteArray() +
                cur.keyId.toByteArray() + cur.key.toByteArray()

            val sig = JSignature.getInstance("Ed25519").apply {
                initVerify(prevPub)
                update(toVerify)
            }
            require(sig.verify(Base64.getDecoder().decode(signedByPrev))) {
                "lineage signature verification failed at node $i"
            }
        }

        if (currentPublicKey != null) {
            val lastNode = nodes.last()
            val sha256 = MessageDigest.getInstance("SHA-256")
            val currentKeyId = "sha256:" + sha256.digest(currentPublicKey.encoded).joinToString("") { "%02x".format(it) }
            require(lastNode.keyId == currentKeyId) {
                "current key does not match lineage leaf node"
            }
        }
    }
}