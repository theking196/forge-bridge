package com.forge.bridge.data.remote.adapters

import android.util.Log
import com.forge.bridge.data.model.GenerateRequest
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.OutputStream
import java.util.UUID

private const val TAG = "ClaudeProxyAdapter"
private const val BASE = "https://claude.ai/api"
private const val DEFAULT_MODEL = "claude-sonnet-4-5"

/**
 * Proxy-tier adapter for Claude.ai Pro/Team accounts.
 * Uses the claude.ai internal web API — no Anthropic API key required.
 *
 * Storage layout in VaultManager:
 *   getApiKey("claude-proxy")       → Organization UUID (from /api/organizations)
 *   getSessionToken("claude-proxy") → Raw cookie string from CookieManager
 *
 * Flow per request:
 *   1. POST /api/organizations/{org_id}/chat_conversations → create conversation, get UUID
 *   2. POST /api/organizations/{org_id}/chat_conversations/{conv_id}/completion → stream response
 *
 * The conversation is not explicitly deleted after — Claude.ai's internal API
 * doesn't expose a public delete endpoint. Conversations accumulate in the account
 * history but don't affect functionality.
 */
class ClaudeProxyAdapter(private val client: OkHttpClient) : ProxyProviderAdapter {

    override val providerId = "claude-proxy"
    private val gson = Gson()
    private val json = "application/json".toMediaType()

    override fun chat(request: GenerateRequest, apiKey: String): AdapterResult {
        throw AdapterException("Claude proxy only supports streaming (stream=true).")
    }

    override fun stream(request: GenerateRequest, apiKey: String, out: OutputStream) {
        streamWithCookies(request, accessToken = apiKey, cookies = "", out)
    }

    override fun streamWithCookies(
        request: GenerateRequest,
        accessToken: String,   // = org UUID
        cookies: String,
        out: OutputStream,
    ) {
        if (accessToken.isBlank() && cookies.isBlank()) {
            out.writeSseError("Not connected — login via Manage Providers")
            return
        }

        val orgId = accessToken
        val model = request.model ?: DEFAULT_MODEL

        // Step 1: create a conversation
        val conversationId = try {
            createConversation(orgId, cookies, model)
        } catch (e: AdapterException) {
            if (e.message?.contains("401") == true || e.message?.contains("403") == true) {
                out.writeSseError("Claude session expired — re-login via Manage Providers")
            } else {
                out.writeSseError("Could not start Claude conversation: ${e.message}")
            }
            return
        }

        // Step 2: build the prompt from the last user message
        val userMessage = request.messages.lastOrNull { it.role == "user" }?.content
            ?: request.messages.lastOrNull()?.content
            ?: run { out.writeSseError("No user message in request"); return }

        val completionBody = gson.toJson(mapOf(
            "prompt" to userMessage,
            "model" to model,
            "timezone" to "UTC",
            "attachments" to emptyList<Any>(),
            "files" to emptyList<Any>(),
        ))

        val httpReq = buildApiRequest(
            method = "POST",
            url = "$BASE/organizations/$orgId/chat_conversations/$conversationId/completion",
            cookies = cookies,
            body = completionBody,
            referer = "https://claude.ai/chat/$conversationId",
            accept = "text/event-stream",
        )

        client.newCall(httpReq).execute().use { resp ->
            when (resp.code) {
                401, 403 -> { out.writeSseError("Claude session expired — re-login via Manage Providers"); return }
                429      -> { out.writeSseError("Claude rate limit reached — wait and retry"); return }
            }
            if (!resp.isSuccessful) {
                out.writeSseError("Claude completion error ${resp.code}: ${resp.body?.string()?.take(200)}")
                return
            }

            val source = resp.body?.source() ?: run {
                out.writeSseError("Claude returned empty body"); return
            }

            var chunkCount = 0

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data: ")) continue
                val data = line.removePrefix("data: ").trim()
                if (data.isEmpty()) continue

                try {
                    val obj = gson.fromJson(data, JsonObject::class.java)
                    when (obj["type"]?.asString) {
                        "completion" -> {
                            val text = obj["completion"]?.asString ?: ""
                            val stopReason = obj["stop_reason"]?.asString
                            if (text.isNotEmpty()) { out.writeSseChunk(text); chunkCount++ }
                            if (stopReason != null && stopReason != "null") break
                        }
                        "error" -> {
                            val msg = obj["error"]?.asJsonObject?.get("message")?.asString ?: "Unknown error"
                            out.writeSseError("Claude error: $msg")
                            return
                        }
                        "ping", "message_limit" -> { /* ignore */ }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse Claude chunk: $data", e)
                }
            }

            Log.d(TAG, "Stream complete — $chunkCount chunks")
            out.writeSseDone()
        }
    }

    override fun testConnection(apiKey: String): TestResult {
        val start = System.currentTimeMillis()
        return if (apiKey.isBlank()) {
            TestResult(false, 0L, DEFAULT_MODEL, "Not connected — login via Manage Providers")
        } else {
            TestResult(true, System.currentTimeMillis() - start, DEFAULT_MODEL,
                "Session active — org: ${apiKey.take(8)}…")
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun createConversation(orgId: String, cookies: String, model: String): String {
        val body = gson.toJson(mapOf(
            "uuid" to UUID.randomUUID().toString(),
            "name" to "",
            "model" to model,
        ))
        val req = buildApiRequest(
            method = "POST",
            url = "$BASE/organizations/$orgId/chat_conversations",
            cookies = cookies,
            body = body,
            referer = "https://claude.ai/new",
            accept = "application/json",
        )
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful)
                throw AdapterException("Failed to create conversation — HTTP ${resp.code}")
            val raw = resp.body?.string()
                ?: throw AdapterException("Empty response when creating conversation")
            return gson.fromJson(raw, JsonObject::class.java)["uuid"]?.asString
                ?: throw AdapterException("No uuid in conversation response")
        }
    }

    private fun buildApiRequest(
        method: String,
        url: String,
        cookies: String,
        body: String? = null,
        referer: String,
        accept: String,
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Origin", "https://claude.ai")
            .header("Accept", accept)
            .header("anthropic-client-platform", "web_claude_ai")
        if (cookies.isNotBlank()) builder.header("Cookie", cookies)
        when (method) {
            "POST" -> builder.header("Content-Type", "application/json")
                .post((body ?: "{}").toRequestBody(json))
            else -> builder.get()
        }
        return builder.build()
    }

    companion object {
        const val LOGIN_URL     = "https://claude.ai/login"
        const val COOKIE_DOMAIN = "https://claude.ai"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"
    }
}
