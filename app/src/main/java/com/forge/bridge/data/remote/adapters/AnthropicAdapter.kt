package com.forge.bridge.data.remote.adapters

import android.util.Log
import com.forge.bridge.data.model.GenerateRequest
import com.forge.bridge.data.model.Message
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.OutputStream

private const val TAG = "AnthropicAdapter"
private const val BASE_URL = "https://api.anthropic.com/v1/messages"
private const val API_VERSION = "2023-06-01"
private const val DEFAULT_MODEL = "claude-haiku-3-5"
private const val TEST_MODEL = "claude-haiku-3-5"
private const val DEFAULT_MAX_TOKENS = 4096

class AnthropicAdapter(private val client: OkHttpClient) : ProviderAdapter {

    override val providerId = "anthropic-api"
    private val gson = Gson()
    private val json = "application/json".toMediaType()

    override fun chat(request: GenerateRequest, apiKey: String): AdapterResult {
        val start = System.currentTimeMillis()
        val model = request.model ?: DEFAULT_MODEL
        val body = buildRequestBody(request, model, stream = false)
        val httpReq = buildHttpRequest(apiKey, body)

        client.newCall(httpReq).execute().use { resp ->
            val raw = resp.body?.string() ?: throw AdapterException("Empty response from Anthropic")
            if (!resp.isSuccessful) throw AdapterException("Anthropic error ${resp.code}: $raw")

            val obj = gson.fromJson(raw, JsonObject::class.java)
            val contentArray = obj["content"]?.asJsonArray ?: JsonArray()

            val textContent = contentArray
                .filter { it.asJsonObject["type"]?.asString == "text" }
                .mapNotNull { it.asJsonObject["text"]?.asString }
                .joinToString("\n")

            val toolUseBlocks = contentArray.filter { it.asJsonObject["type"]?.asString == "tool_use" }
            val toolCallsJson = if (toolUseBlocks.isNotEmpty()) {
                val arr = JsonArray()
                toolUseBlocks.forEach { block ->
                    val b = block.asJsonObject
                    val tc = JsonObject()
                    tc.addProperty("id", b["id"]?.asString ?: "tc_${System.currentTimeMillis()}")
                    tc.addProperty("type", "function")
                    val fn = JsonObject()
                    fn.addProperty("name", b["name"]?.asString ?: "")
                    fn.addProperty("arguments", gson.toJson(b["input"]))
                    tc.add("function", fn)
                    arr.add(tc)
                }
                arr
            } else null

            val usage = obj["usage"]?.asJsonObject
            return AdapterResult(
                content = textContent,
                model = obj["model"]?.asString ?: model,
                inputTokens = usage?.get("input_tokens")?.asInt ?: 0,
                outputTokens = usage?.get("output_tokens")?.asInt ?: 0,
                durationMs = System.currentTimeMillis() - start,
                toolCalls = toolCallsJson,
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
                out.writeSseError("Anthropic error ${resp.code}: $err")
                return
            }

            val source = resp.body?.source() ?: run {
                out.writeSseError("Anthropic returned empty body")
                return
            }

            var inputTokens = 0
            var outputTokens = 0
            var lastEvent = ""

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break

                when {
                    line.startsWith("event: ") -> lastEvent = line.removePrefix("event: ").trim()

                    line.startsWith("data: ") -> {
                        val data = line.removePrefix("data: ").trim()
                        if (data.isEmpty()) continue
                        try {
                            val obj = gson.fromJson(data, JsonObject::class.java)
                            when (lastEvent) {
                                "message_start" -> {
                                    obj["message"]?.asJsonObject
                                        ?.get("usage")?.asJsonObject?.let { u ->
                                            inputTokens = u["input_tokens"]?.asInt ?: 0
                                        }
                                }
                                "content_block_delta" -> {
                                    val text = obj["delta"]?.asJsonObject
                                        ?.get("text")?.asString ?: continue
                                    if (text.isNotEmpty()) out.writeSseChunk(text)
                                }
                                "message_delta" -> {
                                    obj["usage"]?.asJsonObject?.let { u ->
                                        outputTokens = u["output_tokens"]?.asInt ?: outputTokens
                                    }
                                }
                                "message_stop" -> { }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse Anthropic chunk: $data", e)
                        }
                    }
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
        val messages = buildAnthropicMessages(request.messages)
        val map = mutableMapOf<String, Any>(
            "model" to model,
            "max_tokens" to (request.maxTokens ?: DEFAULT_MAX_TOKENS),
            "messages" to messages,
            "stream" to stream,
        )
        if (!request.systemPrompt.isNullOrBlank()) map["system"] = request.systemPrompt
        request.temperature?.let { map["temperature"] = it }
        if (request.tools != null && request.tools.size() > 0) {
            map["tools"] = convertToolsToAnthropic(request.tools)
        }
        return gson.toJson(map)
    }

    private fun buildAnthropicMessages(messages: List<Message>): JsonArray {
        val arr = JsonArray()
        for (msg in messages) {
            when {
                msg.toolCalls != null -> {
                    val content = JsonArray()
                    msg.toolCalls.forEach { tc ->
                        val b = tc.asJsonObject
                        val block = JsonObject()
                        block.addProperty("type", "tool_use")
                        block.addProperty("id", b["id"]?.asString ?: "tc_${System.currentTimeMillis()}")
                        block.addProperty("name", b["function"]?.asJsonObject?.get("name")?.asString ?: "")
                        val argsStr = b["function"]?.asJsonObject?.get("arguments")?.asString ?: "{}"
                        block.add("input", try { gson.fromJson(argsStr, JsonObject::class.java) } catch (_: Exception) { JsonObject() })
                        content.add(block)
                    }
                    val obj = JsonObject()
                    obj.addProperty("role", "assistant")
                    obj.add("content", content)
                    arr.add(obj)
                }
                msg.role == "tool" -> {
                    val result = JsonObject()
                    result.addProperty("type", "tool_result")
                    result.addProperty("tool_use_id", msg.toolCallId ?: "")
                    result.addProperty("content", msg.content ?: "")
                    val content = JsonArray()
                    content.add(result)
                    val obj = JsonObject()
                    obj.addProperty("role", "user")
                    obj.add("content", content)
                    arr.add(obj)
                }
                else -> {
                    val obj = JsonObject()
                    obj.addProperty("role", msg.role)
                    obj.addProperty("content", msg.content ?: "")
                    arr.add(obj)
                }
            }
        }
        return arr
    }

    private fun convertToolsToAnthropic(tools: JsonArray): JsonArray {
        val result = JsonArray()
        tools.forEach { t ->
            val tool = t.asJsonObject
            val fn = tool["function"]?.asJsonObject ?: return@forEach
            val out = JsonObject()
            out.addProperty("name", fn["name"]?.asString ?: "")
            out.addProperty("description", fn["description"]?.asString ?: "")
            out.add("input_schema", fn["parameters"])
            result.add(out)
        }
        return result
    }

    private fun buildHttpRequest(apiKey: String, bodyJson: String) =
        Request.Builder()
            .url(BASE_URL)
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody(json))
            .build()
}
