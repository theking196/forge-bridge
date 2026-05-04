package com.forge.bridge.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Stores API keys and session tokens in Android Keystore-backed encrypted storage.
 * No plaintext ever written to disk.
 */
class VaultManager(private val context: Context) {

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setUserAuthenticationRequired(false)
            .build()
    }

    private val prefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // ── API keys ──────────────────────────────────────────────────────────────

    fun storeApiKey(providerId: String, apiKey: String) {
        prefs.edit().putString(keyFor(providerId), apiKey).apply()
    }

    fun getApiKey(providerId: String): String? =
        prefs.getString(keyFor(providerId), null)

    fun deleteApiKey(providerId: String) {
        prefs.edit().remove(keyFor(providerId)).apply()
    }

    fun hasApiKey(providerId: String): Boolean =
        prefs.contains(keyFor(providerId))

    // ── Session tokens (cookies for proxy/browser tier) ───────────────────────

    fun storeSessionToken(providerId: String, token: String) {
        prefs.edit().putString(sessionKeyFor(providerId), token).apply()
    }

    fun getSessionToken(providerId: String): String? =
        prefs.getString(sessionKeyFor(providerId), null)

    fun deleteSessionToken(providerId: String) {
        prefs.edit().remove(sessionKeyFor(providerId)).apply()
    }

    /**
     * Stable per-install UUID sent as `Oai-Device-Id` on ChatGPT backend calls.
     * ChatGPT ties sentinel + conversation auth to a consistent device id.
     */
    fun getOrCreateOaiDeviceId(): String {
        val existing = prefs.getString(KEY_OAI_DEVICE_ID, null)
        if (existing != null) return existing
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_OAI_DEVICE_ID, id).apply()
        return id
    }

    // ── Forge OS trust ────────────────────────────────────────────────────────

    /**
     * Persists the user's decision to allow (or deny) Forge OS from sending
     * AI requests through the bridge. Once allowed, [ForgeIntentReceiver]
     * dispatches requests directly without showing [PermissionActivity] again.
     */
    fun storeForgeOsTrust(allowed: Boolean) {
        prefs.edit().putBoolean(KEY_FORGE_OS_TRUSTED, allowed).apply()
    }

    fun isForgeOsTrusted(): Boolean =
        prefs.getBoolean(KEY_FORGE_OS_TRUSTED, false)

    fun revokeForgeOsTrust() {
        prefs.edit().remove(KEY_FORGE_OS_TRUSTED).apply()
    }

    // ── Wipe ──────────────────────────────────────────────────────────────────

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    // ── Key builders ──────────────────────────────────────────────────────────

    private fun keyFor(providerId: String) = "apikey_$providerId"
    private fun sessionKeyFor(providerId: String) = "session_$providerId"

    companion object {
        private const val PREFS_FILE = "forge_bridge_vault"
        private const val KEY_FORGE_OS_TRUSTED = "forge_os_trusted"
        private const val KEY_OAI_DEVICE_ID = "oai_device_id_chatgpt"
    }
}
