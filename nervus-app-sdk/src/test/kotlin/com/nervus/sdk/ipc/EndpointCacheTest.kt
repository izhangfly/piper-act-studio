package com.nervus.sdk.ipc

import com.nervus.sdk.ipc.endpoint.EndpointCache
import com.nervus.sdk.ipc.endpoint.EndpointCacheKey
import com.nervus.sdk.ipc.endpoint.EndpointCacheValue
import kotlin.test.Test
import kotlin.test.*

class EndpointCacheTest {

    @Test
    fun `cache miss returns null`() {
        val cache = EndpointCache()
        val key = EndpointCacheKey("com.example.IFoo", 1, 0, byteArrayOf(0x01, 0x02))
        assertNull(cache.get(key))
    }

    @Test
    fun `cache hit after put`() {
        val cache = EndpointCache()
        val key = EndpointCacheKey("com.example.IFoo", 1, 0, byteArrayOf(0x01, 0x02))
        cache.put(key, EndpointCacheValue(42L))
        val value = cache.get(key)
        assertNotNull(value)
        assertEquals(42L, value.endpointId)
    }

    @Test
    fun `different key does not collide`() {
        val cache = EndpointCache()
        val key1 = EndpointCacheKey("com.example.IFoo", 1, 0, byteArrayOf(0x01))
        val key2 = EndpointCacheKey("com.example.IBar", 1, 0, byteArrayOf(0x01))
        cache.put(key1, EndpointCacheValue(42L))
        assertNull(cache.get(key2))
    }

    @Test
    fun `different schema hash is different key`() {
        val cache = EndpointCache()
        val key1 = EndpointCacheKey("com.example.IFoo", 1, 0, byteArrayOf(0x01))
        val key2 = EndpointCacheKey("com.example.IFoo", 1, 0, byteArrayOf(0x02))
        cache.put(key1, EndpointCacheValue(42L))
        assertNull(cache.get(key2))
    }

    @Test
    fun `invalidateAll clears cache`() {
        val cache = EndpointCache()
        cache.put(
            EndpointCacheKey("com.example.IFoo", 1, 0, byteArrayOf(0x01)),
            EndpointCacheValue(42L)
        )
        cache.put(
            EndpointCacheKey("com.example.IBar", 2, 0, byteArrayOf(0x02)),
            EndpointCacheValue(99L)
        )
        assertEquals(2, cache.size())

        cache.invalidateAll()

        assertEquals(0, cache.size())
    }

    @Test
    fun `evicts oldest when over capacity`() {
        val cache = EndpointCache(maxEntries = 2)
        val key1 = EndpointCacheKey("a", 1, 0, byteArrayOf(0x01))
        val key2 = EndpointCacheKey("b", 1, 0, byteArrayOf(0x02))
        val key3 = EndpointCacheKey("c", 1, 0, byteArrayOf(0x03))

        cache.put(key1, EndpointCacheValue(1L))
        cache.put(key2, EndpointCacheValue(2L))
        cache.put(key3, EndpointCacheValue(3L))

        assertEquals(2, cache.size())
        assertNull(cache.get(key1))
        assertNotNull(cache.get(key2))
        assertNotNull(cache.get(key3))
    }
}
