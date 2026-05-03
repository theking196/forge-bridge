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

private const val TAG = "GeminiAdapter"
private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models"
private const val DEFAULT_MODEL = "gemini-2.0-flash"
private const val TEST_MODEL = "gemini-2.0-flash"

class GeminiAdapter(private val client: OkHttpClient) : ProviderAdapter {

    override val providerId = "gemini-api"
    private val gson = Gson()
    private val json = "application/json".toMediaType()

    override fun chat(request: GenerateRequest, apiKey: String): AdapterResult {
        val start = System.currentTimeMillis()
        val model = request.model ?: DEFAULT_MODEL
        val url = "$BASE/$model:generateContent?key=$apiKey"
        val body = buildRequestBody(request)
        val httpReq = Request.Builder().url(url)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(json)).build()

        client.newCall(httpReq).execute().use { resp ->
            val raw = resp.body?.string() ?: throw AdapterException("Empty response from Gemini")
            if (!resp.isSuccessful) throw AdapterException("Gemini error ${resp.code}: $raw")

            val obj = gson.fromJson(raw, JsonObject::class.java)
            val text = extractCandidateText(obj)
            val usage = obj["usageMetadata"]?.asJsonObject
            return AdapterResult(
                content = text,
                model = model,
                inputTokens = usage?.get("promptTokenCount")?.asInt ?: 0,
                outputTokens = usage?.get("candidatesTokenCount")?.asInt ?: 0,
                durationMs = System.currentTimeMillis() - start,
            )
        }
    }

    override fun stream(request: GenerateRequest, apiKey: String, out: OutputStream) {
        val model = request.model ?: DEFAULT_MODEL
        // alt=sse turns the response into SSE — each chunk is a complete GenerateContentResponse
        val url = "$BASE/$model:streamGenerateContent?key=$apiKey&alt=sse"
        val body = buildRequestBody(request)
        val httpReq = Request.Builder().url(url)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(json)).build()

        client.newCall(httpReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string() ?: "HTTP ${resp.code}"
                out.writeSseError("Gemini error ${resp.code}: $err")
                return
            }

            val source = resp.body?.source() ?: run {
                out.writeSseError("Gemini returned empty body")
                return
            }

            var inputTokens = 0
            var outputTokens = 0

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data: ")) continue
                val data = line.removePrefix("data: ").trim()
                if (data.isEmpty()) continue

                try {
                    val obj = gson.fromJson(data, JsonObject::class.java)
                    val text = extractCandidateText(obj)
                    if (text.isNotEmpty()) out.writeSseChunk(text)

                    // usageMetadata arrives on each chunk; last one has totals
                    obj["usageMetadata"]?.asJsonObject?.let { u ->
                        inputTokens = u["promptTokenCount"]?.asInt ?: inputTokens
                        outputTokens = u["candidatesTokenCount"]?.asInt ?: outputTokens
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse Gemini chunk: $data", e)
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
            TestResult(true, System.currentTimeMillis() - start, TEST_MODEL, "OK — model: $TEST_MODEL")
        } catch (e: Exception) {
            TestResult(false, System.currentTimeMillis() - start, TEST_MODEL, e.message ?: "Unknown error")
        }
    }

    // ─── Builders ─────────────────────────────────────────────────────────────

    private fun buildRequestBody(request: GenerateRequest): String {
        // Gemini uses role "model" instead of "assistant"
        val contents = request.messages.map { msg ->
            mapOf(
                "role" to if (msg.role == "assistant") "model" else msg.role,
                "parts" to listOf(mapOf("text" to msg.content)),
            )
        }
        val map = mutableMapOf<String, Any>("contents" to contents)

        if (!request.systemPrompt.isNullOrBlank()) {
            map["system_instruction"] = mapOf(
                "parts" to listOf(mapOf("text" to request.systemPrompt))
            )
        }

        val genConfig = mutableMapOf<String, Any>()
        request.temperature?.let { genConfig["temperature"] = it }
        request.maxTokens?.let { genConfig["maxOutputTokens"] = it }
        if (genConfig.isNotEmpty()) map["generationConfig"] = genConfig

        return gson.toJson(map)
    }

    private fun extractCandidateText(obj: JsonObject): String =
        obj["candidates"]?.asJsonArray
            ?.firstOrNull()?.asJsonObject
            ?.get("content")?.asJsonObject
            ?.get("parts")?.asJsonArray
            ?.fold("") { acc, part -> acc + (part.asJsonObject["text"]?.asString ?: "") }
            ?: ""
}
