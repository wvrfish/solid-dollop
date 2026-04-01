package com.example.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

// ---------------------------------------------------------------------------
// CloudModel
// ---------------------------------------------------------------------------

enum class CloudModel(val modelName: String) {
    Gemini25Flash("gemini-2.5-flash-preview-04-17"),
    Gemini20Flash("gemini-2.0-flash"),
    Gemini15Pro("gemini-1.5-pro"),
}

// ---------------------------------------------------------------------------
// DesktopFirebaseChatModel
// ---------------------------------------------------------------------------

/**
 * Desktop (JVM) implementation of [GenerativeChatModel] using the Gemini
 * Developer API via [GeminiClient] / [GeminiChatSession].
 *
 * Mirrors the Android `FirebaseChatModel` — same function-calling loop and
 * history management — but targets the Gemini REST API over HTTPS instead of
 * the Firebase Android AI SDK.
 *
 * Set the `GEMINI_API_KEY` environment variable to your Google AI Studio key.
 */
class DesktopFirebaseChatModel(
    val cloudModel: CloudModel = CloudModel.Gemini25Flash,
    private val apiKey: String = System.getenv("GEMINI_API_KEY")
        ?: error("GEMINI_API_KEY environment variable is not set"),
    private val generationConfig: GenerationConfig? = null,
    private val safetySettings: List<SafetySetting> = emptyList(),
) : GenerativeChatModel {

    override fun configure(
        instruction: String?,
        history: List<String>,
        schema: AISchema?,
        tools: List<ModelTool<*>>,
    ): GenerativeChatModel.Configured {
        val client = GeminiClient(
            modelName = cloudModel.modelName,
            apiKey = apiKey,
            generationConfig = generationConfig,
            safetySettings = safetySettings,
        )

        val geminiTools = tools
            .map { it.toGeminiFunctionDeclaration() }
            .takeIf { it.isNotEmpty() }
            ?.let { listOf(GeminiTool(it)) }
            ?: emptyList()

        // Even indices → user turns, odd → model turns, matching the Android side.
        val historyContents = history.mapIndexed { i, msg ->
            if (i % 2 == 0) Content.user(msg) else Content.model(msg)
        }

        val session = client.startChat(
            history = historyContents,
            systemInstruction = instruction?.let { Content("system", listOf(TextPart(it))) },
            tools = geminiTools,
        )

        return ConfiguredModel(session = session, tools = tools)
    }

    // -----------------------------------------------------------------------

    class ConfiguredModel(
        private val session: GeminiChatSession,
        private val tools: List<ModelTool<*>>,
    ) : GenerativeChatModel.Configured {

        private val json = Json { ignoreUnknownKeys = true }

        override suspend fun sendMessage(prompt: String): String {
            var response = session.sendMessage(prompt)

            // Function-calling loop — mirrors the Android FirebaseChatModel
            while (response.functionCalls.isNotEmpty()) {
                val functionResponses = response.functionCalls.mapNotNull { call ->
                    val tool = tools.firstOrNull { it.name == call.name } ?: return@mapNotNull null
                    val result = tool.execute(JsonElementMapper, call.args)
                    val decoded = json.decodeFromString<JsonObject>(result)
                    FunctionResponsePart(name = call.name, response = decoded, id = call.id)
                }
                if (functionResponses.isEmpty()) break

                response = session.sendMessage(Content("function", functionResponses))
            }

            return response.text
                ?: throw IllegalStateException("DesktopFirebaseChatModel: model returned no text")
        }
    }
}

// ---------------------------------------------------------------------------
// ModelTool → GeminiFunctionDeclaration
// ---------------------------------------------------------------------------

private fun ModelTool<*>.toGeminiFunctionDeclaration(): GeminiFunctionDeclaration {
    val props = group.params.mapNotNull { p -> p.schema()?.let { p.name to it } }.toMap()
    return GeminiFunctionDeclaration(
        name = name,
        description = description,
        parameters = props.takeIf { it.isNotEmpty() }?.let { AISchema.ObjectType(it) },
    )
}

// ---------------------------------------------------------------------------
// PrimitiveParamMapper for JsonElement
// ---------------------------------------------------------------------------

object JsonElementMapper : PrimitiveParamMapper<JsonElement> {
    override fun toInt(value: JsonElement): Int? = runCatching { value.jsonPrimitive.int }.getOrNull()
    override fun toString(value: JsonElement): String? = runCatching { value.jsonPrimitive.content }.getOrNull()
}
