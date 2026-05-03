package com.forge.bridge.data.remote.adapters

import android.util.Log
import com.forge.bridge.data.model.GenerateRequest
import com.forge.bridge.data.model.Message
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.OutputStream

private const val TAG = "OpenRouterAdapter"
private const val BASE_URL = "https://openrouter.ai/api/v1/chat/completions"
private const val DEFAULT_MODEL = "openai/gpt-4o-mini"
private const val TEST_MODEL = "openai/gpt-4o-mini"

/**
 * OpenRouter adapter — OpenAI-compatible wire format.
 * Supports any model available on openrouter.ai.
 */
class OpenRouterAdapter(private val client: OkHttpClient) : ProviderAdapter {

    override val providerId = "openrouter-api"
    private val gson = Gson()
    private val json = "application/json".toMediaType()

    override fun chat(request: GenerateRequest, apiKey: String): AdapterResult {
        val start = System.currentTimeMillis()
        val model = request.model ?: DEFAULT_MODEL
        val body = buildRequestBody(request, model, stream = false)
        val httpReq = buildHttpRequest(apiKey, body)

        client.newCall(httpReq).execute().use { resp ->
            val raw = resp.body?.string() ?: throw AdapterException("Empty response from OpenRouter")
            if (!resp.isSuccessful) throw AdapterException("OpenRouter error ${resp.code}: $raw")

            val obj = gson.fromJson(raw, JsonObject::class.java)
            val content = obj["choices"]?.asJsonArray
                ?.get(0)?.asJsonObject
                ?.get("message")?.asJsonObject
                ?.get("content")?.asString ?: ""
            val usage = obj["usage"]?.asJsonObject
            return AdapterResult(
                content = content,
                model = obj["model"]?.asString ?: model,
                inputTokens = usage?.get("prompt_tokens")?.asInt ?: 0,
                outputTokens = usage?.get("completion_tokens")?.asInt ?: 0,
                durationMs = System.currentTimeMillis() - start,
            )
        }
    }

    override fun stream(request: GenerateRequest, apiKey: String, out: OutputStream) {
        val model = request.model ?: DEFAULT_MODEL
        val body = buildRequestBody(request, model, stream = true)
        val httpReq = buildHttpRequest(apiKey, body)

        client.newCall(httpReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string() ?: "HTTP ${resp.code}"
                out.writeSseError("OpenRouter error ${resp.code}: $err")
                return
            }

            val source = resp.body?.source() ?: run {
                out.writeSseError("OpenRouter returned empty body")
                return
            }

            var inputTokens = 0
            var outputTokens = 0

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data: ")) continue
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") break
                if (data.isEmpty()) continue

                try {
                    val obj = gson.fromJson(data, JsonObject::class.java)
                    obj["usage"]?.asJsonObject?.let { u ->
                        inputTokens = u["prompt_tokens"]?.asInt ?: inputTokens
                        outputTokens = u["completion_tokens"]?.asInt ?: outputTokens
                    }
                    val delta = obj["choices"]?.asJsonArray
                        ?.get(0)?.asJsonObject
                        ?.get("delta")?.asJsonObject ?: continue
                    val chunk = delta["content"]?.asString ?: continue
                    if (chunk.isNotEmpty()) out.writeSseChunk(chunk)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse OpenRouter chunk: $data", e)
                }
            }

            out.writeSseDone(inputTokens, outputTokens)
        }
    }

    override fun testConnection(apiKey: String): TestResult {
        val start = System.currentTimeMillis()
        return try {
            val result = chat(
                GenerateRequest(
                    provider = providerId,
                    model = TEST_MODEL,
                    messages = listOf(Message("user", "Hi")),
                    stream = false,
                    systemPrompt = null,
                    temperature = null,
                    maxTokens = 5,
                ),
                apiKey,
            )
            TestResult(true, System.currentTimeMillis() - start, result.model, "OK — model: ${result.model}")
        } catch (e: Exception) {
            TestResult(false, System.currentTimeMillis() - start, TEST_MODEL, e.message ?: "Unknown error")
        }
    }

    // ─── Builders ─────────────────────────────────────────────────────────────

    private fun buildRequestBody(request: GenerateRequest, model: String, stream: Boolean): String {
        val messages = buildList {
            if (!request.systemPrompt.isNullOrBlank()) {
                add(mapOf("role" to "system", "content" to request.systemPrompt))
            }
            addAll(request.messages.map { mapOf("role" to it.role, "content" to it.content) })
        }
        val map = mutableMapOf<String, Any>(
            "model" to model,
            "messages" to messages,
            "stream" to stream,
        )
        if (stream) map["stream_options"] = mapOf("include_usage" to true)
        request.temperature?.let { map["temperature"] = it }
        request.maxTokens?.let { map["max_tokens"] = it }
        return gson.toJson(map)
    }

    private fun buildHttpRequest(apiKey: String, bodyJson: String) =
        Request.Builder()
            .url(BASE_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("HTTP-Referer", "https://forge.local")
            .header("X-Title", "Forge Bridge")
            .post(bodyJson.toRequestBody(json))
            .build()
}
