package com.forge.bridge.data.remote.adapters

import android.util.Log
import com.forge.bridge.data.model.GenerateRequest
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.OutputStream

private const val TAG = "OpenAIAdapter"
private const val BASE_URL = "https://api.openai.com/v1/chat/completions"
private const val DEFAULT_MODEL = "gpt-4o-mini"
private const val TEST_MODEL = "gpt-4o-mini"

class OpenAIAdapter(private val client: OkHttpClient) : ProviderAdapter {

    override val providerId = "openai-api"
    private val gson = Gson()
    private val json = "application/json".toMediaType()

    override fun chat(request: GenerateRequest, apiKey: String): AdapterResult {
        val start = System.currentTimeMillis()
        val model = request.model ?: DEFAULT_MODEL
        val body = buildRequestBody(request, model, stream = false)
        val httpReq = buildHttpRequest(apiKey, body)

        client.newCall(httpReq).execute().use { resp ->
            val raw = resp.body?.string() ?: throw AdapterException("Empty response from OpenAI")
            if (!resp.isSuccessful) throw AdapterException("OpenAI error ${resp.code}: $raw")

            val obj = gson.fromJson(raw, JsonObject::class.java)
            val message = obj["choices"]?.asJsonArray
                ?.get(0)?.asJsonObject
                ?.get("message")?.asJsonObject
            val content = message?.get("content")?.asString ?: ""
            val rawToolCalls = message?.get("tool_calls")?.asJsonArray

            val usage = obj["usage"]?.asJsonObject
            return AdapterResult(
                content = content,
                model = obj["model"]?.asString ?: model,
                inputTokens = usage?.get("prompt_tokens")?.asInt ?: 0,
                outputTokens = usage?.get("completion_tokens")?.asInt ?: 0,
                durationMs = System.currentTimeMillis() - start,
                toolCalls = rawToolCalls,
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
                out.writeSseError("OpenAI error ${resp.code}: $err")
                return
            }

            val source = resp.body?.source() ?: run {
                out.writeSseError("OpenAI returned empty body")
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
                    Log.w(TAG, "Failed to parse chunk: $data", e)
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
                    messages = listOf(com.forge.bridge.data.model.Message("user", "Hi")),
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
        val messages = buildJsonMessages(request)
        val map = mutableMapOf<String, Any>(
            "model" to model,
            "messages" to messages,
            "stream" to stream,
        )
        if (stream) map["stream_options"] = mapOf("include_usage" to true)
        request.temperature?.let { map["temperature"] = it }
        request.maxTokens?.let { map["max_tokens"] = it }
        if (request.tools != null && request.tools.size() > 0) {
            map["tools"] = request.tools
            map["tool_choice"] = request.toolChoice ?: "auto"
        }
        return gson.toJson(map)
    }

    private fun buildJsonMessages(request: GenerateRequest): JsonArray {
        val arr = JsonArray()
        if (!request.systemPrompt.isNullOrBlank()) {
            val sys = JsonObject()
            sys.addProperty("role", "system")
            sys.addProperty("content", request.systemPrompt)
            arr.add(sys)
        }
        for (msg in request.messages) {
            val obj = JsonObject()
            obj.addProperty("role", msg.role)
            when {
                msg.toolCalls != null -> {
                    obj.add("tool_calls", msg.toolCalls)
                    if (msg.content != null) obj.addProperty("content", msg.content)
                    else obj.addProperty("content", "")
                }
                msg.role == "tool" -> {
                    obj.addProperty("content", msg.content ?: "")
                    if (msg.toolCallId != null) obj.addProperty("tool_call_id", msg.toolCallId)
                    if (msg.name != null) obj.addProperty("name", msg.name)
                }
                else -> obj.addProperty("content", msg.content ?: "")
            }
            arr.add(obj)
        }
        return arr
    }

    private fun buildHttpRequest(apiKey: String, bodyJson: String) =
        Request.Builder()
            .url(BASE_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody(json))
            .build()
}
