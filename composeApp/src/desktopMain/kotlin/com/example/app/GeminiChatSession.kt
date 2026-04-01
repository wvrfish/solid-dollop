package com.example.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach

/**
 * Stateful multi-turn chat session backed by a [GeminiClient].
 *
 * Mirrors `com.google.ai.client.generativeai.Chat`:
 * - [sendMessage] / [sendMessageStream] append turns to [history] automatically.
 * - [history] reflects all confirmed turns (user + model); in-flight turns are
 *   not visible until the response is received.
 *
 * Obtain instances via [GeminiClient.startChat].
 *
 * **Thread safety**: this class is not thread-safe. Send messages sequentially.
 */
class GeminiChatSession internal constructor(
    private val client: GeminiClient,
    history: List<Content> = emptyList(),
    private val systemInstruction: Content? = null,
    private val tools: List<GeminiTool> = emptyList(),
) {
    private val _history: MutableList<Content> = history.toMutableList()

    /** All confirmed conversation turns, excluding the system instruction. */
    val history: List<Content> get() = _history.toList()

    // -----------------------------------------------------------------------
    // sendMessage
    // -----------------------------------------------------------------------

    /** Send a plain-text user message and return the model's response. */
    suspend fun sendMessage(prompt: String): GenerateContentResponse =
        sendMessage(Content.user(prompt))

    /** Send a user turn built from one or more [Part]s and return the model's response. */
    suspend fun sendMessage(vararg parts: Part): GenerateContentResponse =
        sendMessage(Content.user(*parts))

    /**
     * Send an arbitrary [Content] turn (any role) and return the model's response.
     *
     * Use this overload to inject tool/function-response turns:
     * ```kotlin
     * session.sendMessage(Content("function", listOf(FunctionResponsePart(name, result))))
     * ```
     */
    suspend fun sendMessage(content: Content): GenerateContentResponse {
        _history.add(content)
        return runCatching {
            client.generateContent(_history.toList(), systemInstruction, tools)
        }.onFailure {
            _history.removeLastOrNull() // roll back the turn we optimistically added
        }.getOrThrow().also { response ->
            response.candidates.firstOrNull()?.content?.let { _history.add(it) }
        }
    }

    // -----------------------------------------------------------------------
    // sendMessageStream
    // -----------------------------------------------------------------------

    /** Stream a plain-text user message. Emits partial [GenerateContentResponse]s as chunks arrive. */
    fun sendMessageStream(prompt: String): Flow<GenerateContentResponse> =
        sendMessageStream(Content.user(prompt))

    /** Stream a user turn built from one or more [Part]s. */
    fun sendMessageStream(vararg parts: Part): Flow<GenerateContentResponse> =
        sendMessageStream(Content.user(*parts))

    /**
     * Stream an arbitrary [Content] turn. Emits one [GenerateContentResponse] per SSE chunk;
     * each chunk's [GenerateContentResponse.text] is the *delta* for that chunk.
     *
     * History is updated when the flow completes:
     * - On success — the completed model turn (reconstructed from all deltas) is appended.
     * - On failure / cancellation — the user turn is rolled back.
     */
    fun sendMessageStream(content: Content): Flow<GenerateContentResponse> {
        _history.add(content)
        val accumulated = StringBuilder()
        return client.generateContentStream(_history.toList(), systemInstruction, tools)
            .onEach { response -> response.text?.let { accumulated.append(it) } }
            .onCompletion { cause ->
                if (cause == null) {
                    if (accumulated.isNotEmpty()) _history.add(Content.model(accumulated.toString()))
                } else {
                    _history.removeLastOrNull() // roll back on failure / cancellation
                }
            }
    }
}
