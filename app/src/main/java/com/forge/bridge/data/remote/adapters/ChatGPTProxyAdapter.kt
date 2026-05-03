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
import java.util.UUID

private const val TAG = "ChatGPTProxyAdapter"
private const val BASE_URL         = "https://chatgpt.com"
private const val CONV_URL         = "$BASE_URL/backend-api/conversation"
private const val SESSION_URL      = "$BASE_URL/api/auth/session"
private const val SENTINEL_URL     = "$BASE_URL/backend-api/sentinel/chat-requirements"
private const val DEFAULT_MODEL    = "gpt-4o"

/**
 * Proxy-tier adapter for ChatGPT Plus / Team / Pro accounts.
 * Uses chatgpt.com's internal backend API — no OpenAI API key required.
 *
 * Storage layout in VaultManager:
 *   getApiKey("chatgpt-proxy")       → Bearer access token
 *   getSessionToken("chatgpt-proxy") → Raw cookie string from CookieManager
 *
 * IMPORTANT: ChatGPT's backend uses several bot-protection layers:
 *   1. Valid session cookies (__Secure-next-auth.session-token, _cfuvid, etc.)
 *   2. Authorization: Bearer <access_token> from /api/auth/session
 *   3. OpenAI-Sentinel-Chat-Requirements-Token from /backend-api/sentinel/chat-requirements
 *   4. Oai-Device-Id — a persistent UUID per "device"
 *
 * If any layer fails, the session is likely expired and re-login is required.
 */
class ChatGPTProxyAdapter(private val client: OkHttpClient) : ProxyProviderAdapter {

    override val providerId = "chatgpt-proxy"
    private val gson = Gson()
    private val json = "application/json".toMediaType()

    // Persistent device ID (ideally persisted across calls; held in memory here for simplicity)
    private val deviceId = UUID.randomUUID().toString()

    override fun chat(request: GenerateRequest, apiKey: String): AdapterResult {
        throw AdapterException("ChatGPT proxy only supports streaming (stream=true).")
    }

    /** Called by BridgeServer when no cookies are separately available. */
    override fun stream(request: GenerateRequest, apiKey: String, out: OutputStream) {
        streamWithCookies(request, accessToken = apiKey, cookies = "", out)
    }

    override fun streamWithCookies(
        request: GenerateRequest,
        accessToken: String,
        cookies: String,
        out: OutputStream,
    ) {
        if (accessToken.isBlank() && cookies.isBlank()) {
            out.writeSseError("Not connected — login via Manage Providers")
            return
        }

        val model = request.model ?: DEFAULT_MODEL

        // Step 1: Fetch the sentinel token (anti-bot requirement as of late 2024)
        val sentinelToken = fetchSentinelToken(accessToken, cookies)

        // Step 2: Build and fire the conversation request
        val parentId = UUID.randomUUID().toString()
        val body = buildConversationBody(request.messages, model, parentId, request.systemPrompt)

        val reqBuilder = Request.Builder()
            .url(CONV_URL)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$BASE_URL/")
            .header("Origin", BASE_URL)
            .header("Oai-Device-Id", deviceId)
            .header("Oai-Language", "en-US")

        if (accessToken.isNotBlank()) reqBuilder.header("Authorization", "Bearer $accessToken")
        if (cookies.isNotBlank()) reqBuilder.header("Cookie", cookies)
        if (sentinelToken != null) reqBuilder.header("OpenAI-Sentinel-Chat-Requirements-Token", sentinelToken)

        val httpReq = reqBuilder.post(body.toRequestBody(json)).build()

        client.newCall(httpReq).execute().use { resp ->
            when (resp.code) {
                401, 403 -> {
                    out.writeSseError("Session expired — re-login via Manage Providers")
                    return
                }
                429 -> {
                    out.writeSseError("ChatGPT rate limit reached — wait and retry")
                    return
                }
            }
            if (!resp.isSuccessful) {
                out.writeSseError("ChatGPT error ${resp.code}: ${resp.body?.string()?.take(200)}")
                return
            }

            val source = resp.body?.source() ?: run {
                out.writeSseError("ChatGPT returned empty body")
                return
            }

            var lastContent = ""
            var chunkCount = 0

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data: ")) continue
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") break
                if (data.isEmpty()) continue

                try {
                    val obj = gson.fromJson(data, JsonObject::class.java)

                    // Stream-level errors
                    obj["error"]?.takeIf { !it.isJsonNull }?.asString?.let { err ->
                        out.writeSseError("ChatGPT stream error: $err")
                        return
                    }

                    // ChatGPT sends accumulated content — compute delta manually
                    val parts = obj["message"]?.asJsonObject
                        ?.get("content")?.asJsonObject
                        ?.get("parts")?.asJsonArray ?: continue
                    val fullContent = parts.fold("") { acc, p ->
                        acc + (if (p.isJsonNull) "" else p.asString)
                    }

                    val delta = if (fullContent.length > lastContent.length)
                        fullContent.substring(lastContent.length) else ""
                    lastContent = fullContent

                    if (delta.isNotEmpty()) {
                        out.writeSseChunk(delta)
                        chunkCount++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse ChatGPT chunk", e)
                }
            }

            Log.d(TAG, "Stream complete — $chunkCount chunks, ${lastContent.length} total chars")
            out.writeSseDone()
        }
    }

    override fun testConnection(apiKey: String): TestResult {
        val start = System.currentTimeMillis()
        return try {
            val req = Request.Builder()
                .url(SESSION_URL)
                .header("Authorization", "Bearer $apiKey")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    TestResult(false, System.currentTimeMillis() - start, DEFAULT_MODEL,
                        "Session invalid (HTTP ${resp.code}) — re-login required")
                } else {
                    val body = resp.body?.string() ?: ""
                    val email = runCatching {
                        gson.fromJson(body, JsonObject::class.java)
                            ["user"]?.asJsonObject?.get("email")?.asString
                    }.getOrNull() ?: "unknown"
                    TestResult(true, System.currentTimeMillis() - start, DEFAULT_MODEL,
                        "Session valid — $email")
                }
            }
        } catch (e: Exception) {
            TestResult(false, System.currentTimeMillis() - start, DEFAULT_MODEL,
                "Connection failed: ${e.message}")
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Fetches the Sentinel anti-bot token from ChatGPT.
     * Returns null silently if unreachable — the main request will fail with 403 if truly required.
     */
    private fun fetchSentinelToken(accessToken: String, cookies: String): String? {
        return try {
            val reqBuilder = Request.Builder()
                .url(SENTINEL_URL)
                .header("User-Agent", USER_AGENT)
                .header("Oai-Device-Id", deviceId)
                .post("{}".toRequestBody(json))
            if (accessToken.isNotBlank()) reqBuilder.header("Authorization", "Bearer $accessToken")
            if (cookies.isNotBlank()) reqBuilder.header("Cookie", cookies)

            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                gson.fromJson(body, JsonObject::class.java)["token"]?.asString
            }
        } catch (e: Exception) {
            Log.d(TAG, "Sentinel token unavailable: ${e.message}")
            null
        }
    }

    private fun buildConversationBody(
        messages: List<com.forge.bridge.data.model.Message>,
        model: String,
        parentId: String,
        systemPrompt: String?,
    ): String {
        val allMessages = buildList {
            if (!systemPrompt.isNullOrBlank()) add(chatMsg("system", systemPrompt))
            addAll(messages.map { chatMsg(it.role, it.content) })
        }
        return gson.toJson(mapOf(
            "action" to "next",
            "messages" to allMessages,
            "parent_message_id" to parentId,
            "model" to model,
            "timezone_offset_min" to 0,
            "conversation_mode" to mapOf("kind" to "primary_assistant"),
            "force_use_sse" to true,
            "history_and_training_disabled" to false,
        ))
    }

    private fun chatMsg(role: String, content: String) = mapOf(
        "id" to UUID.randomUUID().toString(),
        "author" to mapOf("role" to role),
        "content" to mapOf("content_type" to "text", "parts" to listOf(content)),
        "metadata" to emptyMap<String, Any>(),
    )

    companion object {
        const val LOGIN_URL     = "https://chatgpt.com/auth/login"
        const val COOKIE_DOMAIN = "https://chatgpt.com"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"
    }
}
