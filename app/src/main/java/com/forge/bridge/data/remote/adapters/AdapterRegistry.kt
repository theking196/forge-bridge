package com.forge.bridge.data.remote.adapters

import android.util.Log
import com.forge.bridge.data.local.VaultManager
import com.forge.bridge.data.remote.browser.BrowserProviderManager
import okhttp3.OkHttpClient

private const val TAG = "AdapterRegistry"

/**
 * Maps provider IDs (as stored in SQLite) to their adapter instances.
 * All adapters share the same OkHttpClient (single connection pool).
 *
 * Tiers:
 *   api     — Official REST API, requires API key (OpenAI, Anthropic, etc.)
 *   proxy   — Web session via WebView cookie extraction (Phase 3)
 *   browser — Headless WebView JS injection, same-origin fetch (Phase 4)
 *
 * Ollama URL Configuration:
 *   The Ollama adapter supports both local (localhost:11434) and remote URLs.
 *   To use a remote Ollama instance, store the URL as the "API key" for ollama-local
 *   via the connect endpoint with setAsDefault=true. The URL format should be:
 *   http://<host>:<port> (e.g., http://192.168.1.100:11434)
 */
class AdapterRegistry(
    client: OkHttpClient,
    browserManager: BrowserProviderManager,
    private val vaultManager: VaultManager,
) {

    private val client = client
    
    // Lazy adapters that can be reconfigured
    private var _ollamaAdapter: OllamaAdapter? = null
    
    val ollama: OllamaAdapter
        get() {
            val savedUrl = vaultManager.getApiKey("ollama-local")
            val url = if (!savedUrl.isNullOrBlank() && savedUrl.startsWith("http")) {
                savedUrl.trimEnd('/')
            } else {
                "http://localhost:11434"
            }
            if (_ollamaAdapter == null || _ollamaAdapter?.baseUrl != url) {
                Log.d(TAG, "Creating Ollama adapter with URL: $url")
                _ollamaAdapter = OllamaAdapter(client, url)
            }
            return _ollamaAdapter!!
        }

    private val staticAdapters: Map<String, ProviderAdapter> by lazy {
        mapOf(
            // ── API tier ──────────────────────────────────────────────────────────
            "openai-api"       to OpenAIAdapter(client),
            "anthropic-api"    to AnthropicAdapter(client),
            "gemini-api"       to GeminiAdapter(client),
            "openrouter-api"   to OpenRouterAdapter(client),
            // ollama-local is handled dynamically via the ollama property
            // ── Proxy tier ────────────────────────────────────────────────────────
            "chatgpt-proxy"    to ChatGPTProxyAdapter(client, vaultManager),
            "claude-proxy"     to ClaudeProxyAdapter(client),
            // ── Browser tier ──────────────────────────────────────────────────────
            "chatgpt-browser"  to BrowserTierAdapter("chatgpt-browser", browserManager),
            "claude-browser"   to BrowserTierAdapter("claude-browser", browserManager),
        )
    }

    /** Returns the adapter for [providerId], or null if not registered. */
    fun get(providerId: String): ProviderAdapter? = when (providerId) {
        "ollama-local" -> ollama
        else -> staticAdapters[providerId]
    }

    /** Returns all registered adapter IDs. */
    fun providerIds(): Set<String> = staticAdapters.keys + "ollama-local"

    /** True if the adapter for this provider is a proxy-tier (WebView login) adapter. */
    fun isProxy(providerId: String): Boolean = get(providerId) is ProxyProviderAdapter

    /** True if the adapter for this provider is a browser-tier (JS injection) adapter. */
    fun isBrowser(providerId: String): Boolean = get(providerId) is BrowserTierAdapter
}
