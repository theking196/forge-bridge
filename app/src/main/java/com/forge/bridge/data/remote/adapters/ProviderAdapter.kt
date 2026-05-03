package com.forge.bridge.data.remote.adapters

import com.forge.bridge.data.model.GenerateRequest
import java.io.OutputStream

/**
 * Unified interface for every AI provider.
 * All methods are synchronous — callers run them on a background thread.
 *
 * Stream output format (written to [OutputStream]):
 *   data: {"content":"chunk","done":false}\n\n
 *   ...
 *   data: {"content":"","done":true,"usage":{"inputTokens":N,"outputTokens":M}}\n\n
 *   data: [DONE]\n\n
 */
interface ProviderAdapter {

    /** Provider ID — matches the `id` column in SQLite. */
    val providerId: String

    /**
     * Non-streaming chat. Blocks until the full response arrives.
     * @throws AdapterException on HTTP or parsing error.
     */
    fun chat(request: GenerateRequest, apiKey: String): AdapterResult

    /**
     * Streaming chat. Writes normalized SSE lines to [out] then closes nothing —
     * the caller is responsible for closing [out] after this returns.
     * @throws AdapterException on HTTP or parsing error.
     */
    fun stream(request: GenerateRequest, apiKey: String, out: OutputStream)

    /**
     * Lightweight connectivity check using the cheapest available model.
     * Should complete in < 5 s.
     */
    fun testConnection(apiKey: String): TestResult
}

// ─── Result types ─────────────────────────────────────────────────────────────

data class AdapterResult(
    val content: String,
    val model: String,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val durationMs: Long = 0,
    val toolCalls: com.google.gson.JsonArray? = null,
)

data class TestResult(
    val success: Boolean,
    val latencyMs: Long,
    val model: String,
    val message: String,
)

class AdapterException(message: String, cause: Throwable? = null) : Exception(message, cause)

// ─── SSE helpers shared by all adapters ──────────────────────────────────────

internal fun OutputStream.writeSseChunk(content: String) {
    val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    write("data: {\"content\":\"$escaped\",\"done\":false}\n\n".toByteArray(Charsets.UTF_8))
}

internal fun OutputStream.writeSseDone(inputTokens: Int = 0, outputTokens: Int = 0) {
    write(
        "data: {\"content\":\"\",\"done\":true,\"usage\":{\"inputTokens\":$inputTokens,\"outputTokens\":$outputTokens}}\n\n"
            .toByteArray(Charsets.UTF_8)
    )
    write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
    flush()
}

internal fun OutputStream.writeSseError(message: String) {
    val escaped = message.replace("\"", "'").replace("\n", " ")
    write("data: {\"error\":\"$escaped\",\"done\":true}\n\n".toByteArray(Charsets.UTF_8))
    write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
    flush()
}
