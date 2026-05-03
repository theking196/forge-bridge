package com.forge.bridge.data.model

import android.database.Cursor
import com.forge.bridge.data.local.Database

// ─── Provider ─────────────────────────────────────────────────────────────────

data class ProviderRow(
    val id: String,
    val name: String,
    val providerType: String,
    val tier: String,
    val status: String,
    val models: String,
    val features: String,
    val description: String,
    val connectedAt: Long?,
    val lastUsedAt: Long?,
    val requestCount: Int,
    val errorCount: Int,
    val isDefault: Boolean,
) {
    companion object {
        fun fromCursor(c: Cursor) = ProviderRow(
            id = c.getString(c.getColumnIndexOrThrow(Database.COL_ID)),
            name = c.getString(c.getColumnIndexOrThrow(Database.COL_NAME)),
            providerType = c.getString(c.getColumnIndexOrThrow(Database.COL_PROVIDER_TYPE)),
            tier = c.getString(c.getColumnIndexOrThrow(Database.COL_TIER)),
            status = c.getString(c.getColumnIndexOrThrow(Database.COL_STATUS)),
            models = c.getString(c.getColumnIndexOrThrow(Database.COL_MODELS)),
            features = c.getString(c.getColumnIndexOrThrow(Database.COL_FEATURES)),
            description = c.getString(c.getColumnIndexOrThrow(Database.COL_DESCRIPTION)),
            connectedAt = c.getLong(c.getColumnIndexOrThrow(Database.COL_CONNECTED_AT))
                .takeIf { !c.isNull(c.getColumnIndexOrThrow(Database.COL_CONNECTED_AT)) },
            lastUsedAt = c.getLong(c.getColumnIndexOrThrow(Database.COL_LAST_USED_AT))
                .takeIf { !c.isNull(c.getColumnIndexOrThrow(Database.COL_LAST_USED_AT)) },
            requestCount = c.getInt(c.getColumnIndexOrThrow(Database.COL_REQUEST_COUNT)),
            errorCount = c.getInt(c.getColumnIndexOrThrow(Database.COL_ERROR_COUNT)),
            isDefault = c.getInt(c.getColumnIndexOrThrow(Database.COL_IS_DEFAULT)) == 1,
        )
    }
}

// ─── Audit Log ────────────────────────────────────────────────────────────────

data class AuditLogEntry(
    val id: Long = 0,
    val timestamp: Long,
    val providerId: String,
    val endpoint: String,
    val statusCode: Int,
    val durationMs: Long,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val model: String? = null,
    val errorMessage: String? = null,
) {
    companion object {
        fun fromCursor(c: Cursor) = AuditLogEntry(
            id = c.getLong(c.getColumnIndexOrThrow(Database.COL_AUDIT_ID)),
            timestamp = c.getLong(c.getColumnIndexOrThrow(Database.COL_AUDIT_TIMESTAMP)),
            providerId = c.getString(c.getColumnIndexOrThrow(Database.COL_AUDIT_PROVIDER_ID)),
            endpoint = c.getString(c.getColumnIndexOrThrow(Database.COL_AUDIT_ENDPOINT)),
            statusCode = c.getInt(c.getColumnIndexOrThrow(Database.COL_AUDIT_STATUS_CODE)),
            durationMs = c.getLong(c.getColumnIndexOrThrow(Database.COL_AUDIT_DURATION_MS)),
            inputTokens = c.getInt(c.getColumnIndexOrThrow(Database.COL_AUDIT_INPUT_TOKENS)),
            outputTokens = c.getInt(c.getColumnIndexOrThrow(Database.COL_AUDIT_OUTPUT_TOKENS)),
            model = c.getString(c.getColumnIndexOrThrow(Database.COL_AUDIT_MODEL)),
            errorMessage = c.getString(c.getColumnIndexOrThrow(Database.COL_AUDIT_ERROR)),
        )
    }
}

// ─── Stats ────────────────────────────────────────────────────────────────────

data class BridgeStats(
    val activeProviders: Int,
    val totalProviders: Int,
    val totalRequests: Int,
    val requestsToday: Int,
    val totalErrors: Int,
    val avgLatencyMs: Int,
    val topProvider: String?,
    val totalTokensUsed: Long,
)

// ─── API Request / Response ───────────────────────────────────────────────────

data class GenerateRequest(
    val provider: String?,
    val model: String?,
    val messages: List<Message>,
    val stream: Boolean = false,
    val systemPrompt: String?,
    val temperature: Double?,
    val maxTokens: Int?,
    val tools: com.google.gson.JsonArray? = null,
    val toolChoice: String? = null,
)

data class Message(
    val role: String,
    val content: String? = null,
    @com.google.gson.annotations.SerializedName("tool_calls")
    val toolCalls: com.google.gson.JsonArray? = null,
    @com.google.gson.annotations.SerializedName("tool_call_id")
    val toolCallId: String? = null,
    val name: String? = null,
)

data class ConnectRequest(
    val apiKey: String?,
    val setAsDefault: Boolean = false,
)
