package com.example.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

// ---------------------------------------------------------------------------
// CloudModel
// ---------------------------------------------------------------------------

enum class CloudModel(val modelName: String) {
    Gemini25Flash("gemini-2.5-flash-preview-04-17"),
    Gemini20Flash("gemini-2.0-flash"),
    Gemini15Pro("gemini-1.5-pro"),
}

// ---------------------------------------------------------------------------
// Gemini JSON helpers
// ---------------------------------------------------------------------------

private fun <P> ModelTool<P>.toFunctionDeclarationJson(): JsonObject = buildJsonObject {
    put("name", name)
    put("description", description)
    val props = group.params.mapNotNull { p -> p.schema()?.let { p.name to it.toGeminiJson() } }
    if (props.isNotEmpty()) {
        putJsonObject("parameters") {
            put("type", "OBJECT")
            putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        }
    }
}

private fun userContent(text: String): JsonObject = buildJsonObject {
    put("role", "user")
    putJsonArray("parts") { add(buildJsonObject { put("text", text) }) }
}

private fun modelContent(text: String): JsonObject = buildJsonObject {
    put("role", "model")
    putJsonArray("parts") { add(buildJsonObject { put("text", text) }) }
}

private fun functionResponseContent(name: String, response: JsonObject): JsonObject = buildJsonObject {
    put("role", "function")
    putJsonArray("parts") {
        add(buildJsonObject {
            putJsonObject("functionResponse") {
                put("name", name)
                put("response", response)
            }
        })
    }
}

// ---------------------------------------------------------------------------
// DesktopFirebaseChatModel
// ---------------------------------------------------------------------------

/**
 * Desktop (JVM) implementation of [GenerativeChatModel] using the Gemini
 * REST API directly via [java.net.http.HttpClient].
 *
 * This mirrors the Android `FirebaseChatModel` but targets the Gemini
 * Developer API instead of the Firebase Android AI SDK. No extra SDK
 * dependency is required beyond `kotlinx-serialization-json`.
 *
 * Set the `GEMINI_API_KEY` environment variable to your Google AI Studio key.
 */
class DesktopFirebaseChatModel(
    val cloudModel: CloudModel = CloudModel.Gemini25Flash,
    private val apiKey: String = System.getenv("GEMINI_API_KEY")
        ?: error("GEMINI_API_KEY environment variable is not set"),
) : GenerativeChatModel {

    private val http = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    override fun configure(
        instruction: String?,
        history: List<String>,
        schema: AISchema?,
        tools: List<ModelTool<*>>,
    ): GenerativeChatModel.Configured {
        val toolsJson: JsonArray? = if (tools.isEmpty()) null else buildJsonArray {
            add(buildJsonObject {
                putJsonArray("functionDeclarations") {
                    tools.forEach { add(it.toFunctionDeclarationJson()) }
                }
            })
        }

        val systemInstruction: JsonObject? = instruction?.let {
            buildJsonObject {
                putJsonArray("parts") { add(buildJsonObject { put("text", it) }) }
            }
        }

        // Seed history: user messages become alternating user/model turns.
        // Odd indices → user, even (non-zero) → model, matching the Android side.
        val historyContents = history.mapIndexed { i, msg ->
            if (i % 2 == 0) userContent(msg) else modelContent(msg)
        }

        return ConfiguredModel(
            apiKey = apiKey,
            modelName = cloudModel.modelName,
            systemInstruction = systemInstruction,
            toolsJson = toolsJson,
            tools = tools,
            history = historyContents.toMutableList(),
            http = http,
            json = json,
        )
    }

    // -----------------------------------------------------------------------

    class ConfiguredModel(
        private val apiKey: String,
        private val modelName: String,
        private val systemInstruction: JsonObject?,
        private val toolsJson: JsonArray?,
        private val tools: List<ModelTool<*>>,
        private val history: MutableList<JsonObject>,
        private val http: HttpClient,
        private val json: Json,
    ) : GenerativeChatModel.Configured {

        private val endpoint: String
            get() = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"

        override suspend fun sendMessage(prompt: String): String = withContext(Dispatchers.IO) {
            history.add(userContent(prompt))

            var responseContent = sendRequest()
            history.add(responseContent)

            // Function-calling loop — mirrors the Android FirebaseChatModel
            while (true) {
                val functionCalls = responseContent["parts"]
                    ?.jsonArray
                    ?.mapNotNull { it.jsonObject["functionCall"]?.jsonObject }
                    ?: break
                if (functionCalls.isEmpty()) break

                val responseParts = functionCalls.mapNotNull { call ->
                    val name = call["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val args = call["args"]?.jsonObject ?: JsonObject(emptyMap())
                    val tool = tools.firstOrNull { it.name == name } ?: return@mapNotNull null
                    val result = tool.execute(JsonElementMapper, args)
                    val decoded = json.decodeFromString<JsonObject>(result)
                    name to decoded
                }
                if (responseParts.isEmpty()) break

                // Append all function responses as a single function-role turn
                responseParts.forEach { (name, decoded) ->
                    history.add(functionResponseContent(name, decoded))
                }

                responseContent = sendRequest()
                history.add(responseContent)
            }

            responseContent["parts"]
                ?.jsonArray
                ?.firstNotNullOfOrNull { it.jsonObject["text"]?.jsonPrimitive?.content }
                ?: throw IllegalStateException("DesktopFirebaseChatModel: model returned no text")
        }

        private fun sendRequest(): JsonObject {
            val body = buildJsonObject {
                systemInstruction?.let { put("systemInstruction", it) }
                putJsonArray("contents") { history.forEach { add(it) } }
                toolsJson?.let { put("tools", it) }
            }

            val request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()

            val responseBody = http.send(request, HttpResponse.BodyHandlers.ofString()).body()
            val parsed = json.decodeFromString<JsonObject>(responseBody)

            val error = parsed["error"]?.jsonObject
            if (error != null) {
                val msg = error["message"]?.jsonPrimitive?.content ?: "unknown error"
                throw IllegalStateException("Gemini API error: $msg")
            }

            return parsed["candidates"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("content")
                ?.jsonObject
                ?: throw IllegalStateException("DesktopFirebaseChatModel: unexpected response shape")
        }
    }
}

// ---------------------------------------------------------------------------
// PrimitiveParamMapper for JsonElement
// ---------------------------------------------------------------------------

object JsonElementMapper : PrimitiveParamMapper<JsonElement> {
    override fun toInt(value: JsonElement): Int? = runCatching { value.jsonPrimitive.int }.getOrNull()
    override fun toString(value: JsonElement): String? = runCatching { value.jsonPrimitive.content }.getOrNull()
}
