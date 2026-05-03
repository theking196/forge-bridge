package com.forge.bridge.data.remote.server

import android.util.Log
import com.forge.bridge.data.local.Database
import com.forge.bridge.data.local.VaultManager
import com.forge.bridge.data.model.AuditLogEntry
import com.forge.bridge.data.model.ConnectRequest
import com.forge.bridge.data.model.GenerateRequest
import com.forge.bridge.data.remote.adapters.AdapterException
import com.forge.bridge.data.remote.adapters.AdapterRegistry
import com.forge.bridge.data.remote.adapters.ProxyProviderAdapter
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Date

private const val TAG = "BridgeServer"
private const val PORT = 8745
private const val HOSTNAME = "127.0.0.1"
private const val VERSION = "0.4.0"
private val STARTED_AT = System.currentTimeMillis()

/**
 * Forge Bridge HTTP server — binds exclusively to 127.0.0.1:8745.
 * Never accessible from outside the device.
 */
class BridgeServer(
    private val db: Database,
    private val vault: VaultManager,
    private val httpClient: OkHttpClient,
    private val adapters: AdapterRegistry,
) : NanoHTTPD(HOSTNAME, PORT) {

    private val gson: Gson = GsonBuilder().serializeNulls().create()

    override fun serve(session: IHTTPSession): Response {
        val origin = session.headers["origin"] ?: ""
        if (origin.isNotEmpty() && !isAllowedOrigin(origin)) return forbidden("Origin not allowed")
        if (session.method == Method.OPTIONS) return corsOk()
        val uri = session.uri.trimEnd('/')
        return try { route(session, uri) } catch (e: Exception) {
            Log.e(TAG, "Unhandled error for ${session.method} $uri", e)
            jsonResponse(500, mapOf("error" to (e.message ?: "Internal server error")))
        }
    }

    // ─── Router ───────────────────────────────────────────────────────────────

    private fun route(session: IHTTPSession, uri: String): Response {
        val method = session.method
        val body: Map<String, String> = mutableMapOf<String, String>().also { map ->
            if (method == Method.POST || method == Method.PUT || method == Method.PATCH)
                session.parseBody(map)
        }
        val bodyJson = body["postData"] ?: ""

        return when {
            method == Method.GET  && uri == "/api/v1/healthz"              -> handleHealthz()
            method == Method.GET  && uri == "/api/v1/providers"            -> handleListProviders()
            method == Method.GET  && uri.matches(PROVIDER_PATH)            -> handleGetProvider(lastSegment(uri))
            method == Method.POST && uri.matches(CONNECT_PATH)             -> handleConnect(providerIdFrom(uri, 2), bodyJson)
            method == Method.POST && uri.matches(DISCONNECT_PATH)          -> handleDisconnect(providerIdFrom(uri, 2))
            method == Method.POST && uri.matches(TEST_PATH)                -> handleTestProvider(providerIdFrom(uri, 2))
            method == Method.POST && uri == "/api/v1/generate"             -> handleGenerate(bodyJson)
            method == Method.GET  && uri == "/api/v1/audit-log"            -> handleAuditLog(session.parameters)
            method == Method.GET  && uri == "/api/v1/stats"                -> handleStats()
            method == Method.GET  && uri == "/api/v1/forge/handshake"      -> handleForgeHandshake()
            method == Method.POST && uri == "/api/v1/forge/action"         -> handleForgeAction(bodyJson)
            method == Method.POST && uri == "/api/v1/forge/trust"          -> handleForgeTrust(bodyJson)
            method == Method.DELETE && uri == "/api/v1/forge/trust"        -> handleRevokeForgeOsTrust()
            else -> jsonResponse(404, mapOf("error" to "Not found: ${session.method} $uri"))
        }
    }

    // ─── Health ───────────────────────────────────────────────────────────────

    private fun handleHealthz() = jsonResponse(200, mapOf(
        "status" to "ok",
        "version" to VERSION,
        "uptimeSeconds" to ((System.currentTimeMillis() - STARTED_AT) / 1000).toInt(),
    ))

    // ─── Providers ────────────────────────────────────────────────────────────

    private fun handleListProviders() =
        jsonResponse(200, mapOf("providers" to db.getProviders().map { it.toApiMap() }))

    private fun handleGetProvider(id: String): Response {
        val p = db.getProvider(id) ?: return jsonResponse(404, mapOf("error" to "Provider not found"))
        return jsonResponse(200, p.toApiMap())
    }

    private fun handleConnect(id: String, bodyJson: String): Response {
        val p = db.getProvider(id) ?: return jsonResponse(404, mapOf("error" to "Provider not found"))
        val isProxy = p.tier == "proxy"
        val isOllama = id == "ollama-local"

        if (isProxy) {
            return jsonResponse(400, mapOf(
                "error" to "Provider '$id' is a proxy-tier provider. " +
                        "Connect it via the Settings UI (Manage Providers → Login).",
                "tier" to "proxy",
            ))
        }

        val req = gson.fromJson(bodyJson, ConnectRequest::class.java)
            ?: return jsonResponse(400, mapOf("error" to "Invalid request body"))
        val apiKey = req.apiKey?.trim()
        if (!isOllama && apiKey.isNullOrEmpty()) {
            return jsonResponse(400, mapOf("error" to "apiKey is required for provider: $id"))
        }

        vault.storeApiKey(id, apiKey ?: "")
        db.updateProviderStatus(id, "connected", System.currentTimeMillis())
        if (req.setAsDefault) db.setDefaultProvider(id)
        Log.i(TAG, "Provider connected: $id")
        return jsonResponse(200, db.getProvider(id)!!.toApiMap())
    }

    private fun handleDisconnect(id: String): Response {
        db.getProvider(id) ?: return jsonResponse(404, mapOf("error" to "Provider not found"))
        vault.deleteApiKey(id)
        vault.deleteSessionToken(id)
        db.updateProviderStatus(id, "disconnected")
        Log.i(TAG, "Provider disconnected: $id")
        return jsonResponse(200, db.getProvider(id)!!.toApiMap())
    }

    private fun handleTestProvider(id: String): Response {
        val p = db.getProvider(id) ?: return jsonResponse(404, mapOf("error" to "Provider not found"))
        if (p.status != "connected") {
            return jsonResponse(200, mapOf("success" to false, "latencyMs" to 0, "model" to "",
                "message" to "Provider is not connected"))
        }
        val adapter = adapters.get(id)
            ?: return jsonResponse(503, mapOf("error" to "No adapter registered for: $id"))
        val apiKey = vault.getApiKey(id) ?: ""
        val result = try { adapter.testConnection(apiKey) } catch (e: Exception) {
            Log.w(TAG, "Test failed for $id", e)
            return jsonResponse(200, mapOf("success" to false, "latencyMs" to 0,
                "model" to "", "message" to (e.message ?: "Unknown error")))
        }
        return jsonResponse(200, mapOf("success" to result.success, "latencyMs" to result.latencyMs,
            "model" to result.model, "message" to result.message))
    }

    // ─── Generate ─────────────────────────────────────────────────────────────

    private fun handleGenerate(bodyJson: String): Response {
        if (bodyJson.isEmpty()) return jsonResponse(400, mapOf("error" to "Request body required"))
        val req = gson.fromJson(bodyJson, GenerateRequest::class.java)
            ?: return jsonResponse(400, mapOf("error" to "Invalid JSON body"))
        if (req.messages.isEmpty()) return jsonResponse(400, mapOf("error" to "messages must not be empty"))

        val providerId = req.provider
            ?: db.getProviders().firstOrNull { it.isDefault }?.id
            ?: db.getProviders().firstOrNull { it.status == "connected" }?.id
            ?: return jsonResponse(503, mapOf("error" to "No connected provider. Connect one via Settings."))

        val provider = db.getProvider(providerId)
            ?: return jsonResponse(404, mapOf("error" to "Provider not found: $providerId"))
        if (provider.status != "connected")
            return jsonResponse(503, mapOf("error" to "Provider $providerId is not connected"))

        val adapter = adapters.get(providerId)
            ?: return jsonResponse(503, mapOf("error" to "No adapter for: $providerId"))
        val apiKey = vault.getApiKey(providerId) ?: ""
        val start = System.currentTimeMillis()

        val shouldStream = req.stream || adapter is ProxyProviderAdapter

        return if (shouldStream) {
            streamingResponse(req, providerId, adapter, apiKey, start)
        } else {
            blockingResponse(req, providerId, adapter, apiKey, start)
        }
    }

    private fun blockingResponse(
        req: GenerateRequest, providerId: String,
        adapter: com.forge.bridge.data.remote.adapters.ProviderAdapter,
        apiKey: String, start: Long,
    ): Response {
        return try {
            val result = adapter.chat(req, apiKey)
            val duration = System.currentTimeMillis() - start
            db.insertAuditLog(AuditLogEntry(timestamp = start, providerId = providerId,
                endpoint = "/api/v1/generate", statusCode = 200, durationMs = duration,
                inputTokens = result.inputTokens, outputTokens = result.outputTokens, model = result.model))
            db.incrementProviderStats(providerId, false)
            val responseMap = mutableMapOf<String, Any?>(
                "content" to result.content,
                "provider" to providerId,
                "model" to result.model,
                "usage" to mapOf("inputTokens" to result.inputTokens,
                    "outputTokens" to result.outputTokens,
                    "totalTokens" to (result.inputTokens + result.outputTokens)),
                "durationMs" to duration,
            )
            if (result.toolCalls != null && result.toolCalls.size() > 0) {
                responseMap["toolCalls"] = result.toolCalls
            }
            jsonResponse(200, responseMap)
        } catch (e: AdapterException) {
            Log.w(TAG, "Adapter error for $providerId", e)
            db.incrementProviderStats(providerId, true)
            jsonResponse(502, mapOf("error" to (e.message ?: "Provider error")))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error", e)
            db.incrementProviderStats(providerId, true)
            jsonResponse(500, mapOf("error" to (e.message ?: "Internal error")))
        }
    }

    private fun streamingResponse(
        req: GenerateRequest, providerId: String,
        adapter: com.forge.bridge.data.remote.adapters.ProviderAdapter,
        apiKey: String, start: Long,
    ): Response {
        val pipedOut = PipedOutputStream()
        val pipedIn  = PipedInputStream(pipedOut, 65536)

        val cookies = if (adapter is ProxyProviderAdapter) vault.getSessionToken(providerId) ?: "" else ""

        Thread {
            try {
                if (adapter is ProxyProviderAdapter) {
                    adapter.streamWithCookies(req, accessToken = apiKey, cookies = cookies, out = pipedOut)
                } else {
                    adapter.stream(req, apiKey, pipedOut)
                }
                db.incrementProviderStats(providerId, false)
            } catch (e: Exception) {
                Log.e(TAG, "Stream error for $providerId", e)
                try {
                    val msg = e.message?.replace("\"", "'") ?: "Stream error"
                    pipedOut.write("data: {\"error\":\"$msg\",\"done\":true}\n\n".toByteArray())
                    pipedOut.write("data: [DONE]\n\n".toByteArray())
                    pipedOut.flush()
                    db.incrementProviderStats(providerId, true)
                } catch (_: Exception) {}
            } finally {
                db.insertAuditLog(AuditLogEntry(timestamp = start, providerId = providerId,
                    endpoint = "/api/v1/generate", statusCode = 200,
                    durationMs = System.currentTimeMillis() - start, model = req.model))
                try { pipedOut.close() } catch (_: Exception) {}
            }
        }.start()

        val resp = newChunkedResponse(Response.Status.OK, "text/event-stream", pipedIn)
        resp.addHeader("Cache-Control", "no-cache")
        resp.addHeader("Connection", "keep-alive")
        addCorsHeaders(resp)
        return resp
    }

    // ─── Audit / Stats ────────────────────────────────────────────────────────

    private fun handleAuditLog(params: Map<String, List<String>>): Response {
        val limit = params["limit"]?.firstOrNull()?.toIntOrNull()?.coerceIn(1, 500) ?: 50
        val entries = db.getAuditLog(limit, params["providerId"]?.firstOrNull())
        return jsonResponse(200, mapOf("entries" to entries.map { it.toApiMap() }, "total" to entries.size))
    }

    private fun handleStats(): Response {
        val s = db.getStats()
        return jsonResponse(200, mapOf(
            "activeProviders" to s.activeProviders, "totalProviders" to s.totalProviders,
            "totalRequests" to s.totalRequests, "requestsToday" to s.requestsToday,
            "totalErrors" to s.totalErrors, "avgLatencyMs" to s.avgLatencyMs,
            "uptimeSeconds" to ((System.currentTimeMillis() - STARTED_AT) / 1000).toInt(),
            "topProvider" to s.topProvider, "totalTokensUsed" to s.totalTokensUsed,
        ))
    }

    // ─── Forge OS ─────────────────────────────────────────────────────────────

    private fun handleForgeHandshake(): Response {
        val connected = db.getProviders().filter { it.status == "connected" }.map { it.toApiMap() }
        return jsonResponse(200, mapOf(
            "forgeBridge" to true,
            "version" to VERSION,
            "providers" to connected,
            "uptimeSeconds" to ((System.currentTimeMillis() - STARTED_AT) / 1000).toInt(),
            "capabilities" to listOf("chat", "streaming", "audit-log", "multi-provider",
                "proxy-tier", "browser-tier", "mdns-discovery", "intent-receiver"),
            "mdns" to mapOf(
                "serviceType" to "_forge-bridge._tcp.",
                "serviceName" to "ForgeBridge",
                "port" to PORT,
            ),
            "intentAction" to "com.forge.ACTION_AI_REQUEST",
            "trustedForgeOs" to vault.isForgeOsTrusted(),
        ))
    }

    /**
     * Handles structured Forge OS actions.
     * Supported action types:
     *  - `generate`       — runs a chat generation (same as POST /api/v1/generate)
     *  - `list_providers` — returns connected providers
     *  - `status`         — returns server health
     */
    private fun handleForgeAction(bodyJson: String): Response {
        if (bodyJson.isEmpty()) return jsonResponse(400, mapOf("error" to "Request body required"))
        val body = try {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(bodyJson, Map::class.java) as? Map<String, Any?>
        } catch (e: Exception) { null }
            ?: return jsonResponse(400, mapOf("error" to "Invalid JSON"))

        return when (val action = body["action"] as? String) {
            "generate" -> {
                val message = body["message"] as? String
                    ?: return jsonResponse(400, mapOf("error" to "message is required for action=generate"))
                val provider = body["provider"] as? String
                val model = body["model"] as? String
                val generateBody = gson.toJson(buildMap {
                    if (provider != null) put("provider", provider)
                    if (model != null) put("model", model)
                    put("messages", listOf(mapOf("role" to "user", "content" to message)))
                    put("stream", false)
                })
                handleGenerate(generateBody)
            }
            "list_providers" -> handleListProviders()
            "status" -> handleHealthz()
            null -> jsonResponse(400, mapOf("error" to "action is required"))
            else -> jsonResponse(400, mapOf("error" to "Unknown action: $action. Supported: generate, list_providers, status"))
        }
    }

    /** Programmatically grant or revoke Forge OS trust (mirrors PermissionActivity). */
    private fun handleForgeTrust(bodyJson: String): Response {
        val body = try {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(bodyJson, Map::class.java) as? Map<String, Any?>
        } catch (e: Exception) { null }
            ?: return jsonResponse(400, mapOf("error" to "Invalid JSON"))
        val allowed = body["allowed"] as? Boolean
            ?: return jsonResponse(400, mapOf("error" to "allowed (boolean) is required"))
        vault.storeForgeOsTrust(allowed)
        Log.i(TAG, "Forge OS trust set to $allowed via API")
        return jsonResponse(200, mapOf("trusted" to allowed))
    }

    private fun handleRevokeForgeOsTrust(): Response {
        vault.revokeForgeOsTrust()
        return jsonResponse(200, mapOf("trusted" to false))
    }

    // ─── HTTP helpers ─────────────────────────────────────────────────────────

    private fun jsonResponse(statusCode: Int, data: Any): Response {
        val resp = newFixedLengthResponse(
            Status.lookup(statusCode) ?: Status.INTERNAL_ERROR,
            "application/json", gson.toJson(data),
        )
        addCorsHeaders(resp)
        return resp
    }

    private fun addCorsHeaders(resp: Response) {
        resp.addHeader("Access-Control-Allow-Origin", "http://localhost:*")
        resp.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        resp.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        resp.addHeader("Cache-Control", "no-store")
    }

    private fun corsOk() = newFixedLengthResponse(Status.OK, "text/plain", "").also { addCorsHeaders(it) }
    private fun forbidden(msg: String) =
        newFixedLengthResponse(Status.FORBIDDEN, "application/json", """{"error":"$msg"}""")

    private fun isAllowedOrigin(o: String) =
        o.startsWith("http://localhost") || o.startsWith("http://127.0.0.1")

    private fun lastSegment(uri: String) = uri.split("/").last()
    private fun providerIdFrom(uri: String, fromEnd: Int) =
        uri.split("/").let { it[it.size - fromEnd] }

    companion object {
        private val PROVIDER_PATH   = Regex("/api/v1/providers/[^/]+")
        private val CONNECT_PATH    = Regex("/api/v1/providers/[^/]+/connect")
        private val DISCONNECT_PATH = Regex("/api/v1/providers/[^/]+/disconnect")
        private val TEST_PATH       = Regex("/api/v1/providers/[^/]+/test")
    }
}

private fun com.forge.bridge.data.model.ProviderRow.toApiMap(): Map<String, Any?> = mapOf(
    "id" to id, "name" to name, "providerType" to providerType, "tier" to tier,
    "status" to status, "models" to models, "features" to features, "description" to description,
    "connectedAt" to connectedAt?.let { Date(it).toString() },
    "lastUsedAt" to lastUsedAt?.let { Date(it).toString() },
    "requestCount" to requestCount, "errorCount" to errorCount, "isDefault" to isDefault,
)

private fun AuditLogEntry.toApiMap(): Map<String, Any?> = mapOf(
    "id" to id, "timestamp" to Date(timestamp).toString(),
    "providerId" to providerId, "endpoint" to endpoint,
    "statusCode" to statusCode, "durationMs" to durationMs,
    "inputTokens" to inputTokens, "outputTokens" to outputTokens,
    "model" to model, "errorMessage" to errorMessage,
)
