package com.example.app

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// ---------------------------------------------------------------------------
// Content / Parts
// ---------------------------------------------------------------------------

data class Content(val role: String, val parts: List<Part>) {
    companion object {
        fun user(text: String) = Content("user", listOf(TextPart(text)))
        fun user(vararg parts: Part) = Content("user", parts.toList())
        fun model(text: String) = Content("model", listOf(TextPart(text)))
        fun model(vararg parts: Part) = Content("model", parts.toList())
    }
}

sealed interface Part

data class TextPart(val text: String) : Part

data class FunctionCallPart(
    val name: String,
    val args: Map<String, JsonElement>,
    val id: String? = null,
) : Part

data class FunctionResponsePart(
    val name: String,
    val response: JsonObject,
    val id: String? = null,
) : Part

/** Inline binary data (e.g. an image). [data] is raw bytes; the client base64-encodes it on the wire. */
class BlobPart(val mimeType: String, val data: ByteArray) : Part

// ---------------------------------------------------------------------------
// Generation config
// ---------------------------------------------------------------------------

data class GenerationConfig(
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val maxOutputTokens: Int? = null,
    val stopSequences: List<String>? = null,
    /** Force a specific MIME type for the response, e.g. "application/json". */
    val responseMimeType: String? = null,
    /** When [responseMimeType] is "application/json", constrain output to this schema. */
    val responseSchema: AISchema? = null,
)

// ---------------------------------------------------------------------------
// Safety
// ---------------------------------------------------------------------------

enum class HarmCategory {
    HARM_CATEGORY_UNSPECIFIED,
    HARM_CATEGORY_HARASSMENT,
    HARM_CATEGORY_HATE_SPEECH,
    HARM_CATEGORY_SEXUALLY_EXPLICIT,
    HARM_CATEGORY_DANGEROUS_CONTENT,
}

enum class HarmBlockThreshold {
    HARM_BLOCK_THRESHOLD_UNSPECIFIED,
    BLOCK_LOW_AND_ABOVE,
    BLOCK_MEDIUM_AND_ABOVE,
    BLOCK_HIGH_AND_ABOVE,
    BLOCK_NONE,
}

enum class HarmProbability {
    HARM_PROBABILITY_UNSPECIFIED,
    NEGLIGIBLE, LOW, MEDIUM, HIGH,
}

data class SafetySetting(val category: HarmCategory, val threshold: HarmBlockThreshold)
data class SafetyRating(val category: HarmCategory?, val probability: HarmProbability?)

// ---------------------------------------------------------------------------
// Tools
// ---------------------------------------------------------------------------

data class GeminiFunctionDeclaration(
    val name: String,
    val description: String,
    /** Parameter schema; use [AISchema.ObjectType] with a property per parameter. */
    val parameters: AISchema? = null,
)

data class GeminiTool(val functionDeclarations: List<GeminiFunctionDeclaration>)

// ---------------------------------------------------------------------------
// Response
// ---------------------------------------------------------------------------

enum class FinishReason {
    FINISH_REASON_UNSPECIFIED, STOP, MAX_TOKENS, SAFETY, RECITATION, OTHER,
}

data class Candidate(
    val content: Content,
    val finishReason: FinishReason?,
    val safetyRatings: List<SafetyRating>,
    val index: Int,
)

data class UsageMetadata(
    val promptTokenCount: Int,
    val candidatesTokenCount: Int,
    val totalTokenCount: Int,
)

data class GenerateContentResponse(
    val candidates: List<Candidate>,
    val usageMetadata: UsageMetadata?,
) {
    /** Concatenated text of all [TextPart]s in the first candidate, or null if absent. */
    val text: String?
        get() = candidates.firstOrNull()?.content?.parts
            ?.filterIsInstance<TextPart>()
            ?.joinToString("") { it.text }
            ?.takeIf { it.isNotEmpty() }

    /** All [FunctionCallPart]s from the first candidate. */
    val functionCalls: List<FunctionCallPart>
        get() = candidates.firstOrNull()?.content?.parts
            ?.filterIsInstance<FunctionCallPart>()
            ?: emptyList()
}

data class CountTokensResponse(val totalTokens: Int)

// ---------------------------------------------------------------------------
// Exceptions
// ---------------------------------------------------------------------------

class GeminiApiException(message: String, val httpCode: Int? = null) : Exception(message)
