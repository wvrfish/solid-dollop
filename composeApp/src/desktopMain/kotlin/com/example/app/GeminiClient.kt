package com.example.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Thin network client for the Gemini `generateContent` REST endpoint.
 *
 * Responsibilities:
 *  - URL construction and API-key auth
 *  - HTTP POST via [java.net.http.HttpClient]
 *  - JSON serialisation / deserialisation
 *  - API-level error detection
 *  - Extraction of the `candidates[0].content` object from the response
 *
 * Everything above the wire (conversation history, function-calling loop,
 * tool dispatch) lives in the caller.
 */
class GeminiClient(
    private val apiKey: String,
    private val modelName: String,
    private val http: HttpClient = HttpClient.newHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val endpoint: String
        get() = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"

    /**
     * POST a `generateContent` request and return the first candidate's
     * `content` object, or throw on API / network errors.
     */
    fun generateContent(
        systemInstruction: JsonObject?,
        contents: List<JsonObject>,
        tools: JsonArray?,
    ): JsonObject {
        val body = buildJsonObject {
            systemInstruction?.let { put("systemInstruction", it) }
            putJsonArray("contents") { contents.forEach { add(it) } }
            tools?.let { put("tools", it) }
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        val responseBody = http.send(request, HttpResponse.BodyHandlers.ofString()).body()
        val parsed = json.decodeFromString<JsonObject>(responseBody)

        parsed["error"]?.jsonObject?.let { err ->
            val msg = err["message"]?.jsonPrimitive?.content ?: "unknown error"
            throw IllegalStateException("Gemini API error: $msg")
        }

        return parsed["candidates"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("content")
            ?.jsonObject
            ?: throw IllegalStateException("GeminiClient: unexpected response shape")
    }

    fun decodeJson(raw: String): JsonObject = json.decodeFromString(raw)
}
