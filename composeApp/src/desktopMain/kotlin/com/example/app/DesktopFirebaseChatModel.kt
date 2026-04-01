package com.example.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

// ---------------------------------------------------------------------------
// CloudModel
// ---------------------------------------------------------------------------

enum class CloudModel(val modelName: String) {
    Gemini25Flash("gemini-2.5-flash-preview-04-17"),
    Gemini20Flash("gemini-2.0-flash"),
    Gemini15Pro("gemini-1.5-pro"),
}

// ---------------------------------------------------------------------------
// Gemini content-building helpers
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// DesktopFirebaseChatModel
// ---------------------------------------------------------------------------

/**
 * Desktop (JVM) implementation of [GenerativeChatModel] using the Gemini
 * REST API via [GeminiClient].
 *
 * Mirrors the Android `FirebaseChatModel` — same function-calling loop,
 * same history management — but targets the Gemini Developer API over HTTP.
 *
 * Set the `GEMINI_API_KEY` environment variable to your Google AI Studio key.
 */
class DesktopFirebaseChatModel(
    val cloudModel: CloudModel = CloudModel.Gemini25Flash,
    private val apiKey: String = System.getenv("GEMINI_API_KEY")
        ?: error("GEMINI_API_KEY environment variable is not set"),
) : GenerativeChatModel {

    override fun configure(
        instruction: String?,
        history: List<String>,
        schema: AISchema?,
        tools: List<ModelTool<*>>,
    ): GenerativeChatModel.Configured {
        val client = GeminiClient(apiKey = apiKey, modelName = cloudModel.modelName)

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

        // Seed history: even indices → user turns, odd → model turns.
        val historyContents = history.mapIndexed { i, msg ->
            if (i % 2 == 0) userContent(msg) else modelContent(msg)
        }

        return ConfiguredModel(
            client = client,
            systemInstruction = systemInstruction,
            toolsJson = toolsJson,
            tools = tools,
            history = historyContents.toMutableList(),
        )
    }

    // -----------------------------------------------------------------------

    class ConfiguredModel(
        private val client: GeminiClient,
        private val systemInstruction: JsonObject?,
        private val toolsJson: JsonArray?,
        private val tools: List<ModelTool<*>>,
        private val history: MutableList<JsonObject>,
    ) : GenerativeChatModel.Configured {

        override suspend fun sendMessage(prompt: String): String = withContext(Dispatchers.IO) {
            history.add(userContent(prompt))

            var responseContent = client.generateContent(systemInstruction, history, toolsJson)
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
                    name to client.decodeJson(result)
                }
                if (responseParts.isEmpty()) break

                responseParts.forEach { (name, decoded) ->
                    history.add(functionResponseContent(name, decoded))
                }

                responseContent = client.generateContent(systemInstruction, history, toolsJson)
                history.add(responseContent)
            }

            responseContent["parts"]
                ?.jsonArray
                ?.firstNotNullOfOrNull { it.jsonObject["text"]?.jsonPrimitive?.content }
                ?: throw IllegalStateException("DesktopFirebaseChatModel: model returned no text")
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
