package com.forge.bridge.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.forge.bridge.data.model.AuditLogEntry
import com.forge.bridge.data.model.BridgeStats
import com.forge.bridge.data.model.ProviderRow

class Database(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_PROVIDERS_TABLE)
        db.execSQL(CREATE_AUDIT_LOG_TABLE)
        db.execSQL(INDEX_AUDIT_LOG_TIMESTAMP)
        db.execSQL(INDEX_AUDIT_LOG_PROVIDER)
        seedApiProviders(db)
        seedProxyProviders(db)
        seedBrowserProviders(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) seedProxyProviders(db)
        if (oldVersion < 3) seedBrowserProviders(db)
    }

    private fun seedApiProviders(db: SQLiteDatabase) {
        listOf(
            ProviderSeed("openai-api", "OpenAI", "openai", "api",
                """["gpt-4o","gpt-4o-mini","gpt-4.1","o3-mini"]""",
                """["chat","streaming","tools"]""",
                "Official OpenAI API — requires API key from platform.openai.com"),
            ProviderSeed("anthropic-api", "Anthropic Claude", "anthropic", "api",
                """["claude-opus-4-5","claude-sonnet-4-5","claude-haiku-3-5"]""",
                """["chat","streaming","tools"]""",
                "Official Anthropic API — requires API key from console.anthropic.com"),
            ProviderSeed("gemini-api", "Google Gemini", "gemini", "api",
                """["gemini-2.5-pro","gemini-2.5-flash","gemini-2.0-flash"]""",
                """["chat","streaming"]""",
                "Official Gemini API — requires API key from aistudio.google.com"),
            ProviderSeed("openrouter-api", "OpenRouter", "openrouter", "api",
                """["anthropic/claude-opus-4","openai/gpt-4o","google/gemini-2.5-pro"]""",
                """["chat","streaming","tools"]""",
                "Unified model router — requires API key from openrouter.ai"),
            ProviderSeed("ollama-local", "Ollama (Local/Remote)", "ollama", "api",
                """[]""",
                """["chat","streaming"]""",
                "Ollama instance — local (localhost:11434) or remote. Connect with URL like http://192.168.1.x:11434"),
        ).forEach { db.insertIgnore(it) }
    }

    private fun seedProxyProviders(db: SQLiteDatabase) {
        listOf(
            ProviderSeed("chatgpt-proxy", "ChatGPT (Pro/Plus)", "chatgpt_proxy", "proxy",
                """["gpt-4o","gpt-4.5","o1","o3"]""",
                """["chat","streaming","web-search","image-gen"]""",
                "ChatGPT Plus/Pro via web session — no API key, login with your account"),
            ProviderSeed("claude-proxy", "Claude (Pro/Team)", "claude_proxy", "proxy",
                """["claude-opus-4-5","claude-sonnet-4-5"]""",
                """["chat","streaming","artifacts"]""",
                "Claude Pro/Team via web session — no API key, login with your account"),
        ).forEach { db.insertIgnore(it) }
    }

    private fun seedBrowserProviders(db: SQLiteDatabase) {
        listOf(
            ProviderSeed("chatgpt-browser", "ChatGPT (Browser JS)", "chatgpt_browser", "browser",
                """["gpt-4o","gpt-4.5","o1","o3"]""",
                """["chat","streaming","web-search","image-gen"]""",
                "ChatGPT via headless WebView JS bridge — uses same login as proxy tier"),
            ProviderSeed("claude-browser", "Claude (Browser JS)", "claude_browser", "browser",
                """["claude-opus-4-5","claude-sonnet-4-5"]""",
                """["chat","streaming","artifacts"]""",
                "Claude via headless WebView JS bridge — uses same login as proxy tier"),
        ).forEach { db.insertIgnore(it) }
    }

    private data class ProviderSeed(
        val id: String, val name: String, val type: String, val tier: String,
        val models: String, val features: String, val description: String,
    )

    private fun SQLiteDatabase.insertIgnore(s: ProviderSeed) {
        insertWithOnConflict(TABLE_PROVIDERS, null, ContentValues().apply {
            put(COL_ID, s.id); put(COL_NAME, s.name)
            put(COL_PROVIDER_TYPE, s.type); put(COL_TIER, s.tier)
            put(COL_STATUS, "disconnected"); put(COL_MODELS, s.models)
            put(COL_FEATURES, s.features); put(COL_DESCRIPTION, s.description)
            put(COL_REQUEST_COUNT, 0); put(COL_ERROR_COUNT, 0); put(COL_IS_DEFAULT, 0)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun getProviders(): List<ProviderRow> =
        readableDatabase.query(TABLE_PROVIDERS, null, null, null, null, null, null)
            .use { c -> buildList { while (c.moveToNext()) add(ProviderRow.fromCursor(c)) } }

    fun getProvider(id: String): ProviderRow? =
        readableDatabase.query(TABLE_PROVIDERS, null, "$COL_ID = ?", arrayOf(id), null, null, null)
            .use { c -> if (c.moveToFirst()) ProviderRow.fromCursor(c) else null }

    fun updateProviderStatus(id: String, status: String, connectedAt: Long? = null) {
        writableDatabase.update(TABLE_PROVIDERS, ContentValues().apply {
            put(COL_STATUS, status)
            if (connectedAt != null) put(COL_CONNECTED_AT, connectedAt)
        }, "$COL_ID = ?", arrayOf(id))
    }

    fun setDefaultProvider(id: String) {
        writableDatabase.execSQL("UPDATE $TABLE_PROVIDERS SET $COL_IS_DEFAULT = 0")
        writableDatabase.update(TABLE_PROVIDERS, ContentValues().apply {
            put(COL_IS_DEFAULT, 1)
        }, "$COL_ID = ?", arrayOf(id))
    }

    fun incrementProviderStats(id: String, isError: Boolean) {
        val col = if (isError) COL_ERROR_COUNT else COL_REQUEST_COUNT
        writableDatabase.execSQL(
            "UPDATE $TABLE_PROVIDERS SET $col = $col + 1, $COL_LAST_USED_AT = ? WHERE $COL_ID = ?",
            arrayOf(System.currentTimeMillis(), id)
        )
    }

    fun insertAuditLog(entry: AuditLogEntry) {
        writableDatabase.insert(TABLE_AUDIT_LOG, null, ContentValues().apply {
            put(COL_AUDIT_TIMESTAMP, entry.timestamp)
            put(COL_AUDIT_PROVIDER_ID, entry.providerId)
            put(COL_AUDIT_ENDPOINT, entry.endpoint)
            put(COL_AUDIT_STATUS_CODE, entry.statusCode)
            put(COL_AUDIT_DURATION_MS, entry.durationMs)
            put(COL_AUDIT_INPUT_TOKENS, entry.inputTokens)
            put(COL_AUDIT_OUTPUT_TOKENS, entry.outputTokens)
            put(COL_AUDIT_MODEL, entry.model)
            put(COL_AUDIT_ERROR, entry.errorMessage)
        })
    }

    fun getAuditLog(limit: Int = 50, providerId: String? = null): List<AuditLogEntry> {
        val sel = if (providerId != null) "$COL_AUDIT_PROVIDER_ID = ?" else null
        val args = if (providerId != null) arrayOf(providerId) else null
        return readableDatabase.query(
            TABLE_AUDIT_LOG, null, sel, args,
            null, null, "$COL_AUDIT_TIMESTAMP DESC", "$limit"
        ).use { c -> buildList { while (c.moveToNext()) add(AuditLogEntry.fromCursor(c)) } }
    }

    fun getStats(): BridgeStats {
        val db = readableDatabase
        fun count(sql: String, args: Array<String>? = null) =
            db.rawQuery(sql, args).use { it.moveToFirst(); it.getInt(0) }
        val dayAgo = (System.currentTimeMillis() - 86_400_000L).toString()
        return BridgeStats(
            activeProviders = count("SELECT COUNT(*) FROM $TABLE_PROVIDERS WHERE $COL_STATUS='connected'"),
            totalProviders = count("SELECT COUNT(*) FROM $TABLE_PROVIDERS"),
            totalRequests = count("SELECT COUNT(*) FROM $TABLE_AUDIT_LOG"),
            requestsToday = count("SELECT COUNT(*) FROM $TABLE_AUDIT_LOG WHERE $COL_AUDIT_TIMESTAMP > ?", arrayOf(dayAgo)),
            totalErrors = count("SELECT COUNT(*) FROM $TABLE_AUDIT_LOG WHERE $COL_AUDIT_STATUS_CODE >= 400"),
            avgLatencyMs = count("SELECT COALESCE(AVG($COL_AUDIT_DURATION_MS), 0) FROM $TABLE_AUDIT_LOG"),
            topProvider = db.rawQuery(
                "SELECT $COL_AUDIT_PROVIDER_ID, COUNT(*) c FROM $TABLE_AUDIT_LOG GROUP BY $COL_AUDIT_PROVIDER_ID ORDER BY c DESC LIMIT 1",
                null
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null },
            totalTokensUsed = db.rawQuery(
                "SELECT COALESCE(SUM($COL_AUDIT_INPUT_TOKENS + $COL_AUDIT_OUTPUT_TOKENS), 0) FROM $TABLE_AUDIT_LOG",
                null
            ).use { it.moveToFirst(); it.getLong(0) },
        )
    }

    companion object {
        private const val DB_NAME = "forge_bridge.db"
        private const val DB_VERSION = 3

        const val TABLE_PROVIDERS = "providers"
        const val COL_ID = "id"
        const val COL_NAME = "name"
        const val COL_PROVIDER_TYPE = "provider_type"
        const val COL_TIER = "tier"
        const val COL_STATUS = "status"
        const val COL_MODELS = "models"
        const val COL_FEATURES = "features"
        const val COL_DESCRIPTION = "description"
        const val COL_CONNECTED_AT = "connected_at"
        const val COL_LAST_USED_AT = "last_used_at"
        const val COL_REQUEST_COUNT = "request_count"
        const val COL_ERROR_COUNT = "error_count"
        const val COL_IS_DEFAULT = "is_default"

        const val TABLE_AUDIT_LOG = "audit_log"
        const val COL_AUDIT_ID = "id"
        const val COL_AUDIT_TIMESTAMP = "timestamp"
        const val COL_AUDIT_PROVIDER_ID = "provider_id"
        const val COL_AUDIT_ENDPOINT = "endpoint"
        const val COL_AUDIT_STATUS_CODE = "status_code"
        const val COL_AUDIT_DURATION_MS = "duration_ms"
        const val COL_AUDIT_INPUT_TOKENS = "input_tokens"
        const val COL_AUDIT_OUTPUT_TOKENS = "output_tokens"
        const val COL_AUDIT_MODEL = "model"
        const val COL_AUDIT_ERROR = "error_message"

        private val CREATE_PROVIDERS_TABLE = """
            CREATE TABLE $TABLE_PROVIDERS (
                $COL_ID TEXT PRIMARY KEY,
                $COL_NAME TEXT NOT NULL,
                $COL_PROVIDER_TYPE TEXT NOT NULL,
                $COL_TIER TEXT NOT NULL DEFAULT 'api',
                $COL_STATUS TEXT NOT NULL DEFAULT 'disconnected',
                $COL_MODELS TEXT NOT NULL DEFAULT '[]',
                $COL_FEATURES TEXT NOT NULL DEFAULT '[]',
                $COL_DESCRIPTION TEXT NOT NULL DEFAULT '',
                $COL_CONNECTED_AT INTEGER,
                $COL_LAST_USED_AT INTEGER,
                $COL_REQUEST_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_ERROR_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_IS_DEFAULT INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()

        private val CREATE_AUDIT_LOG_TABLE = """
            CREATE TABLE $TABLE_AUDIT_LOG (
                $COL_AUDIT_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_AUDIT_TIMESTAMP INTEGER NOT NULL,
                $COL_AUDIT_PROVIDER_ID TEXT NOT NULL,
                $COL_AUDIT_ENDPOINT TEXT NOT NULL,
                $COL_AUDIT_STATUS_CODE INTEGER NOT NULL,
                $COL_AUDIT_DURATION_MS INTEGER NOT NULL,
                $COL_AUDIT_INPUT_TOKENS INTEGER DEFAULT 0,
                $COL_AUDIT_OUTPUT_TOKENS INTEGER DEFAULT 0,
                $COL_AUDIT_MODEL TEXT,
                $COL_AUDIT_ERROR TEXT
            )
        """.trimIndent()

        private const val INDEX_AUDIT_LOG_TIMESTAMP =
            "CREATE INDEX idx_audit_ts ON $TABLE_AUDIT_LOG($COL_AUDIT_TIMESTAMP)"
        private const val INDEX_AUDIT_LOG_PROVIDER =
            "CREATE INDEX idx_audit_provider ON $TABLE_AUDIT_LOG($COL_AUDIT_PROVIDER_ID)"
    }
}
