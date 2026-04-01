package com.example.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

/**
 * HTTP client for the Gemini Generative Language REST API.
 *
 * Mirrors the interface of `com.google.ai.client.generativeai.GenerativeModel`:
 * - [generateContent] — single-turn or multi-turn text generation
 * - [generateContentStream] — streaming generation via SSE, returned as a [Flow]
 * - [countTokens] — prompt token counting without generating a response
 * - [startChat] — creates a stateful [GeminiChatSession] (mirrors `startChat()`)
 *
 * Construct a client per model; share the underlying [HttpClient] across instances
 * via the [httpClient] parameter if connection reuse matters.
 *
 * Obtain an API key from Google AI Studio and supply it via `GEMINI_API_KEY`.
 */
class GeminiClient(
    val modelName: String,
    private val apiKey: String,
    val generationConfig: GenerationConfig? = null,
    val safetySettings: List<SafetySetting> = emptyList(),
    httpClient: HttpClient = HttpClient.newHttpClient(),
) {
    internal val json = Json { ignoreUnknownKeys = true }
    private val http = httpClient

    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta/models/$modelName"

    // -----------------------------------------------------------------------
    // generateContent
    // -----------------------------------------------------------------------

    /** Single-turn generation from a plain text prompt. */
    suspend fun generateContent(
        prompt: String,
        systemInstruction: Content? = null,
        tools: List<GeminiTool> = emptyList(),
    ): GenerateContentResponse = generateContent(
        contents = listOf(Content.user(prompt)),
        systemInstruction = systemInstruction,
        tools = tools,
    )

    /** Single-turn generation from one or more [Part]s. */
    suspend fun generateContent(
        vararg parts: Part,
        systemInstruction: Content? = null,
        tools: List<GeminiTool> = emptyList(),
    ): GenerateContentResponse = generateContent(
        contents = listOf(Content.user(*parts)),
        systemInstruction = systemInstruction,
        tools = tools,
    )

    /** Multi-turn generation from an explicit list of [Content] turns. */
    suspend fun generateContent(
        contents: List<Content>,
        systemInstruction: Content? = null,
        tools: List<GeminiTool> = emptyList(),
    ): GenerateContentResponse = withContext(Dispatchers.IO) {
        post("$baseUrl:generateContent?key=$apiKey", buildRequestBody(contents, systemInstruction, tools))
            .toGenerateContentResponse()
    }

    // -----------------------------------------------------------------------
    // generateContentStream
    // -----------------------------------------------------------------------

    /** Streaming generation from a plain text prompt. Emits partial [GenerateContentResponse]s. */
    fun generateContentStream(
        prompt: String,
        systemInstruction: Content? = null,
        tools: List<GeminiTool> = emptyList(),
    ): Flow<GenerateContentResponse> = generateContentStream(
        contents = listOf(Content.user(prompt)),
        systemInstruction = systemInstruction,
        tools = tools,
    )

    /** Streaming generation from one or more [Part]s. Emits partial [GenerateContentResponse]s. */
    fun generateContentStream(
        vararg parts: Part,
        systemInstruction: Content? = null,
        tools: List<GeminiTool> = emptyList(),
    ): Flow<GenerateContentResponse> = generateContentStream(
        contents = listOf(Content.user(*parts)),
        systemInstruction = systemInstruction,
        tools = tools,
    )

    /**
     * Streaming generation from an explicit list of [Content] turns.
     *
     * Uses the `streamGenerateContent` endpoint with `alt=sse`. The returned [Flow] emits one
     * [GenerateContentResponse] per SSE event; each response's [GenerateContentResponse.text]
     * is the text *delta* for that chunk, not the cumulative text.
     */
    fun generateContentStream(
        contents: List<Content>,
        systemInstruction: Content? = null,
        tools: List<GeminiTool> = emptyList(),
    ): Flow<GenerateContentResponse> = flow {
        val body = buildRequestBody(contents, systemInstruction, tools)
        val request = buildRequest("$baseUrl:streamGenerateContent?alt=sse&key=$apiKey", body)
        val response = http.send(request, HttpResponse.BodyHandlers.ofLines())
        // Use iterator() so emit() (a suspend fun) is called in a regular for-loop,
        // not inside a Java Stream forEach lambda which lacks a suspend context.
        response.body().use { lines ->
            for (line in lines.iterator()) {
                if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ").trim()
                    if (data.isNotEmpty()) {
                        runCatching {
                            emit(json.decodeFromString<JsonObject>(data).toGenerateContentResponse())
                        }
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    // -----------------------------------------------------------------------
    // countTokens
    // -----------------------------------------------------------------------

    /** Count tokens for a plain text prompt without generating a response. */
    suspend fun countTokens(
        prompt: String,
        systemInstruction: Content? = null,
        tools: List<GeminiTool> = emptyList(),
    ): CountTokensResponse = countTokens(
        contents = listOf(Content.user(prompt)),
        systemInstruction = systemInstruction,
        tools = tools,
    )

    /** Count tokens for one or more [Part]s without generating a response. */
    suspend fun countTokens(
        vararg parts: Part,
        systemInstruction: Content? = null,
        tools: List<GeminiTool> = emptyList(),
    ): CountTokensResponse = countTokens(
        contents = listOf(Content.user(*parts)),
        systemInstruction = systemInstruction,
        tools = tools,
    )

    /** Count tokens for an explicit list of [Content] turns without generating a response. */
    suspend fun countTokens(
        contents: List<Content>,
        systemInstruction: Content? = null,
        tools: List<GeminiTool> = emptyList(),
    ): CountTokensResponse = withContext(Dispatchers.IO) {
        val raw = post("$baseUrl:countTokens?key=$apiKey", buildRequestBody(contents, systemInstruction, tools))
        CountTokensResponse(raw["totalTokens"]?.jsonPrimitive?.int ?: 0)
    }

    // -----------------------------------------------------------------------
    // startChat
    // -----------------------------------------------------------------------

    /**
     * Create a stateful [GeminiChatSession], optionally seeded with prior [history].
     * Mirrors `GenerativeModel.startChat()` in the Google AI SDK.
     */
    fun startChat(
        history: List<Content> = emptyList(),
        systemInstruction: Content? = null,
        tools: List<GeminiTool> = emptyList(),
    ): GeminiChatSession = GeminiChatSession(
        client = this,
        history = history,
        systemInstruction = systemInstruction,
        tools = tools,
    )

    // -----------------------------------------------------------------------
    // Internal HTTP helpers
    // -----------------------------------------------------------------------

    private fun buildRequestBody(
        contents: List<Content>,
        systemInstruction: Content?,
        tools: List<GeminiTool>,
    ): JsonObject = buildJsonObject {
        systemInstruction?.let { put("systemInstruction", it.toJson()) }
        putJsonArray("contents") { contents.forEach { add(it.toJson()) } }
        if (tools.isNotEmpty()) {
            putJsonArray("tools") { tools.forEach { add(it.toJson()) } }
        }
        generationConfig?.let { put("generationConfig", it.toJson()) }
        if (safetySettings.isNotEmpty()) {
            putJsonArray("safetySettings") { safetySettings.forEach { add(it.toJson()) } }
        }
    }

    private fun post(url: String, body: JsonObject): JsonObject {
        val raw = http.send(buildRequest(url, body), HttpResponse.BodyHandlers.ofString()).body()
        val parsed = json.decodeFromString<JsonObject>(raw)
        parsed["error"]?.jsonObject?.let { err ->
            throw GeminiApiException(
                message = err["message"]?.jsonPrimitive?.content ?: "unknown Gemini API error",
                httpCode = err["code"]?.jsonPrimitive?.int,
            )
        }
        return parsed
    }

    private fun buildRequest(url: String, body: JsonObject): HttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
}

// ---------------------------------------------------------------------------
// JSON → domain type converters
// ---------------------------------------------------------------------------

private fun JsonObject.toGenerateContentResponse(): GenerateContentResponse {
    val candidates = this["candidates"]?.jsonArray
        ?.mapIndexed { i, el -> el.jsonObject.toCandidate(i) }
        ?: emptyList()
    return GenerateContentResponse(
        candidates = candidates,
        usageMetadata = this["usageMetadata"]?.jsonObject?.toUsageMetadata(),
    )
}

private fun JsonObject.toCandidate(index: Int): Candidate {
    val finishReason = this["finishReason"]?.jsonPrimitive?.content?.let {
        runCatching { FinishReason.valueOf(it) }.getOrDefault(FinishReason.FINISH_REASON_UNSPECIFIED)
    }
    return Candidate(
        content = this["content"]?.jsonObject?.toContent() ?: Content("model", emptyList()),
        finishReason = finishReason,
        safetyRatings = this["safetyRatings"]?.jsonArray?.map { it.jsonObject.toSafetyRating() } ?: emptyList(),
        index = index,
    )
}

private fun JsonObject.toContent(): Content {
    val parts = this["parts"]?.jsonArray?.mapNotNull { it.jsonObject.toPart() } ?: emptyList()
    return Content(role = this["role"]?.jsonPrimitive?.content ?: "model", parts = parts)
}

private fun JsonObject.toPart(): Part? {
    this["text"]?.jsonPrimitive?.content?.let { return TextPart(it) }
    this["functionCall"]?.jsonObject?.let { fc ->
        val name = fc["name"]?.jsonPrimitive?.content ?: return null
        return FunctionCallPart(
            name = name,
            args = fc["args"]?.jsonObject ?: JsonObject(emptyMap()),
            id = fc["id"]?.jsonPrimitive?.content,
        )
    }
    this["functionResponse"]?.jsonObject?.let { fr ->
        val name = fr["name"]?.jsonPrimitive?.content ?: return null
        return FunctionResponsePart(name = name, response = fr["response"]?.jsonObject ?: JsonObject(emptyMap()))
    }
    return null
}

private fun JsonObject.toUsageMetadata() = UsageMetadata(
    promptTokenCount = this["promptTokenCount"]?.jsonPrimitive?.int ?: 0,
    candidatesTokenCount = this["candidatesTokenCount"]?.jsonPrimitive?.int ?: 0,
    totalTokenCount = this["totalTokenCount"]?.jsonPrimitive?.int ?: 0,
)

private fun JsonObject.toSafetyRating() = SafetyRating(
    category = this["category"]?.jsonPrimitive?.content?.let { runCatching { HarmCategory.valueOf(it) }.getOrNull() },
    probability = this["probability"]?.jsonPrimitive?.content?.let { runCatching { HarmProbability.valueOf(it) }.getOrNull() },
)

// ---------------------------------------------------------------------------
// Domain type → JSON converters  (internal — used by GeminiChatSession too)
// ---------------------------------------------------------------------------

internal fun Content.toJson(): JsonObject = buildJsonObject {
    put("role", role)
    putJsonArray("parts") { parts.forEach { add(it.toJson()) } }
}

internal fun Part.toJson(): JsonObject = when (this) {
    is TextPart -> buildJsonObject { put("text", text) }
    is FunctionCallPart -> buildJsonObject {
        putJsonObject("functionCall") {
            put("name", name)
            put("args", JsonObject(args))
        }
    }
    is FunctionResponsePart -> buildJsonObject {
        putJsonObject("functionResponse") {
            put("name", name)
            put("response", response)
        }
    }
    is BlobPart -> buildJsonObject {
        putJsonObject("inlineData") {
            put("mimeType", mimeType)
            put("data", java.util.Base64.getEncoder().encodeToString(data))
        }
    }
}

private fun GeminiTool.toJson(): JsonObject = buildJsonObject {
    putJsonArray("functionDeclarations") { functionDeclarations.forEach { add(it.toJson()) } }
}

private fun GeminiFunctionDeclaration.toJson(): JsonObject = buildJsonObject {
    put("name", name)
    put("description", description)
    parameters?.let { put("parameters", it.toGeminiJson()) }
}

private fun GenerationConfig.toJson(): JsonObject = buildJsonObject {
    temperature?.let { put("temperature", it) }
    topP?.let { put("topP", it) }
    topK?.let { put("topK", it) }
    maxOutputTokens?.let { put("maxOutputTokens", it) }
    stopSequences?.takeIf { it.isNotEmpty() }?.let { seqs ->
        putJsonArray("stopSequences") { seqs.forEach { add(JsonPrimitive(it)) } }
    }
    responseMimeType?.let { put("responseMimeType", it) }
    responseSchema?.let { put("responseSchema", it.toGeminiJson()) }
}

private fun SafetySetting.toJson(): JsonObject = buildJsonObject {
    put("category", category.name)
    put("threshold", threshold.name)
}
