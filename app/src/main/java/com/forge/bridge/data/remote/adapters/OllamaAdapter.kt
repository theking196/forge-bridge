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

private const val TAG = "OllamaAdapter"
private const val DEFAULT_BASE_URL = "http://localhost:11434"
private const val DEFAULT_MODEL = "llama3.2"

/**
 * Ollama adapter — talks to a local or remote Ollama instance.
 * Ollama uses NDJSON (newline-delimited JSON), not SSE.
 * We normalize its output to the same SSE format as other adapters.
 *
 * The baseUrl can be customized to connect to remote Ollama instances
 * (e.g., http://192.168.1.x:11434 for LAN servers).
 */
class OllamaAdapter(
    private val client: OkHttpClient,
    val baseUrl: String = DEFAULT_BASE_URL
) : ProviderAdapter {

    override val providerId = "ollama-local"
    private val gson = Gson()
    private val json = "application/json".toMediaType()
    
    private val chatEndpoint: String get() = "$baseUrl/api/chat"
    private val tagsEndpoint: String get() = "$baseUrl/api/tags"

    // Ollama needs no API key — we still accept the param for interface consistency.

    override fun chat(request: GenerateRequest, apiKey: String): AdapterResult {
        val start = System.currentTimeMillis()
        val model = request.model ?: detectDefaultModel() ?: DEFAULT_MODEL
        val body = buildRequestBody(request, model, stream = false)
        val httpReq = buildHttpRequest(body)

        client.newCall(httpReq).execute().use { resp ->
            val raw = resp.body?.string() ?: throw AdapterException("Empty response from Ollama")
            if (!resp.isSuccessful) throw AdapterException("Ollama error ${resp.code}: $raw")

            // Non-streaming: single JSON object
            val obj = gson.fromJson(raw, JsonObject::class.java)
            val content = obj["message"]?.asJsonObject?.get("content")?.asString ?: ""
            return AdapterResult(
                content = content,
                model = obj["model"]?.asString ?: model,
                inputTokens = obj["prompt_eval_count"]?.asInt ?: 0,
                outputTokens = obj["eval_count"]?.asInt ?: 0,
                durationMs = System.currentTimeMillis() - start,
            )
        }
    }

    override fun stream(request: GenerateRequest, apiKey: String, out: OutputStream) {
        val model = request.model ?: detectDefaultModel() ?: DEFAULT_MODEL
        val body = buildRequestBody(request, model, stream = true)
        val httpReq = buildHttpRequest(body)

        client.newCall(httpReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string() ?: "HTTP ${resp.code}"
                out.writeSseError("Ollama error ${resp.code}: $err")
                return
            }

            val source = resp.body?.source() ?: run {
                out.writeSseError("Ollama returned empty body")
                return
            }

            var inputTokens = 0
            var outputTokens = 0

            // Ollama streams newline-delimited JSON — not SSE
            while (!source.exhausted()) {
                val line = source.readUtf8Line()?.trim() ?: break
                if (line.isEmpty()) continue

                try {
                    val obj = gson.fromJson(line, JsonObject::class.java)
                    val done = obj["done"]?.asBoolean ?: false
                    val chunk = obj["message"]?.asJsonObject?.get("content")?.asString ?: ""
                    if (chunk.isNotEmpty()) out.writeSseChunk(chunk)

                    if (done) {
                        inputTokens = obj["prompt_eval_count"]?.asInt ?: 0
                        outputTokens = obj["eval_count"]?.asInt ?: 0
                        break
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse Ollama chunk: $line", e)
                }
            }

            out.writeSseDone(inputTokens, outputTokens)
        }
    }

    override fun testConnection(apiKey: String): TestResult {
        val start = System.currentTimeMillis()
        return try {
            // Ping the tags endpoint first (no model needed)
            val tagsReq = Request.Builder().url(tagsEndpoint).get().build()
            client.newCall(tagsReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return TestResult(false, System.currentTimeMillis() - start, DEFAULT_MODEL,
                        "Ollama not reachable (HTTP ${resp.code}) — is it running at $baseUrl?")
                }
            }
            val model = detectDefaultModel() ?: DEFAULT_MODEL
            TestResult(true, System.currentTimeMillis() - start, model,
                "Ollama reachable at $baseUrl — first model: $model")
        } catch (e: Exception) {
            TestResult(false, System.currentTimeMillis() - start, DEFAULT_MODEL,
                "Ollama not reachable at $baseUrl — ${e.message}. Start with: ollama serve")
        }
    }

    /** Returns the first model from Ollama's model list, or null if unreachable / empty. */
    fun detectDefaultModel(): String? {
        return try {
            val req = Request.Builder().url(tagsEndpoint).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val obj = gson.fromJson(resp.body?.string() ?: return null, JsonObject::class.java)
                obj["models"]?.asJsonArray?.firstOrNull()?.asJsonObject?.get("name")?.asString
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not detect Ollama models: ${e.message}")
            null
        }
    }

    /** Returns all locally installed model names. */
    fun listLocalModels(): List<String> {
        return try {
            val req = Request.Builder().url(tagsEndpoint).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val obj = gson.fromJson(resp.body?.string() ?: return emptyList(), JsonObject::class.java)
                obj["models"]?.asJsonArray
                    ?.mapNotNull { it.asJsonObject["name"]?.asString }
                    ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
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
        val options = mutableMapOf<String, Any>()
        request.temperature?.let { options["temperature"] = it }
        request.maxTokens?.let { options["num_predict"] = it }
        if (options.isNotEmpty()) map["options"] = options
        return gson.toJson(map)
    }

    private fun buildHttpRequest(bodyJson: String) =
        Request.Builder()
            .url(chatEndpoint)
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody(json))
            .build()
}
