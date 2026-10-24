package com.nervus.act.engine

import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** What the engine said went wrong, in its own words (or why it could not be reached). */
class EngineException(message: String) : Exception(message)

/**
 * The ACT engine's HTTP API (act/engine/nervus_act/server.py), on loopback.
 *
 * The engine owns the arm, the cameras and the shared folder; this app only asks it to do things and shows
 * what it reports. Every call is short except loading a policy, which can take a minute the first time.
 */
class EngineClient(private val base: String = "http://127.0.0.1:8795") {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    fun get(path: String, timeoutMs: Long = 5_000): JsonElement = send(
        HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofMillis(timeoutMs)).GET().build(),
    )

    fun post(path: String, body: Map<String, Any?> = emptyMap(), timeoutMs: Long = 20_000): JsonElement = send(
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(toJson(body).toString()))
            .build(),
    )

    /** Raw bytes (camera frames, thumbnails), or null when there is nothing to show. */
    fun bytes(path: String, timeoutMs: Long = 2_000): ByteArray? = try {
        val r = http.send(
            HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofMillis(timeoutMs)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        if (r.statusCode() == 200) r.body() else null
    } catch (e: Exception) {
        null
    }

    private fun send(req: HttpRequest): JsonElement {
        val r = try {
            http.send(req, HttpResponse.BodyHandlers.ofString())
        } catch (e: ConnectException) {
            throw EngineException("The ACT engine is not running (nervus-act.service). It starts with NervusOS.")
        } catch (e: HttpTimeoutException) {
            throw EngineException("The ACT engine did not answer in time.")
        } catch (e: java.io.IOException) {
            throw EngineException("Lost the ACT engine: ${e.message ?: e.javaClass.simpleName}")
        }
        val body = r.body().orEmpty()
        val json = try {
            if (body.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(body)
        } catch (e: Exception) {
            throw EngineException("The engine sent something that is not JSON (HTTP ${r.statusCode()})")
        }
        if (r.statusCode() !in 200..299) {
            throw EngineException(json["error"].str ?: "HTTP ${r.statusCode()}")
        }
        return json
    }
}

// ---------------------------------------------------------------------------------------------- JSON
// The API is dynamic and changes with the engine, so the app reads it as a tree rather than binding classes.

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
operator fun JsonElement?.get(i: Int): JsonElement? = (this as? JsonArray)?.getOrNull(i)
val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.takeIf { this !is JsonNull }?.contentOrNull
val JsonElement?.dbl: Double? get() = (this as? JsonPrimitive)?.takeIf { this !is JsonNull }?.doubleOrNull
val JsonElement?.int: Int? get() = dbl?.toInt()
val JsonElement?.bool: Boolean get() = (this as? JsonPrimitive)?.booleanOrNull ?: false
val JsonElement?.arr: List<JsonElement> get() = (this as? JsonArray) ?: emptyList()
val JsonElement?.obj: Map<String, JsonElement> get() = (this as? JsonObject) ?: emptyMap()
val JsonElement?.isNull: Boolean get() = this == null || this is JsonNull

fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is String -> JsonPrimitive(v)
    is Map<*, *> -> buildJsonObject { v.forEach { (k, x) -> put(k.toString(), toJson(x)) } }
    is Iterable<*> -> buildJsonArray { v.forEach { add(toJson(it)) } }
    else -> JsonPrimitive(v.toString())
}

/** A number for the screen: fixed decimals, or a dash when there is none. */
fun JsonElement?.fmt(decimals: Int = 1, unit: String = ""): String =
    dbl?.let { "%.${decimals}f".format(it) + unit } ?: "–"
