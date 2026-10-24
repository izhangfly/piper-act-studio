package com.nervus.sdk.ipc.endpoint

import java.util.LinkedHashMap

internal data class EndpointCacheKey(
    val interfaceId: String,
    val interfaceMajor: Int,
    val interfaceMinor: Int,
    val schemaHash: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EndpointCacheKey) return false
        return interfaceId == other.interfaceId &&
                interfaceMajor == other.interfaceMajor &&
                interfaceMinor == other.interfaceMinor &&
                schemaHash.contentEquals(other.schemaHash)
    }

    override fun hashCode(): Int {
        var result = interfaceId.hashCode()
        result = 31 * result + interfaceMajor
        result = 31 * result + interfaceMinor
        result = 31 * result + schemaHash.contentHashCode()
        return result
    }
}

internal data class EndpointCacheValue(
    val endpointId: Long,
    val interfaceMajor: Int = 0,
    val interfaceMinor: Int = 0,
    val schemaHash: ByteArray = ByteArray(0),
    val resourceHandle: String = "",
    val resolvedAtMs: Long = System.currentTimeMillis()
)

internal class EndpointCache(private val maxEntries: Int = 64) {
    private val cache = object : LinkedHashMap<EndpointCacheKey, EndpointCacheValue>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<EndpointCacheKey, EndpointCacheValue>?): Boolean {
            return size > maxEntries
        }
    }

    @Synchronized
    fun get(key: EndpointCacheKey): EndpointCacheValue? {
        return cache[key]
    }

    @Synchronized
    fun put(key: EndpointCacheKey, value: EndpointCacheValue) {
        cache[key] = value
    }

    @Synchronized
    fun invalidateAll() {
        cache.clear()
    }

    @Synchronized
    fun size(): Int = cache.size
}
