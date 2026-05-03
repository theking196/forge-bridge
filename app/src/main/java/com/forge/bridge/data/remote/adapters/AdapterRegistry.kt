package com.forge.bridge.data.remote.adapters

import com.forge.bridge.data.remote.browser.BrowserProviderManager
import okhttp3.OkHttpClient

/**
 * Maps provider IDs (as stored in SQLite) to their adapter instances.
 * All adapters share the same OkHttpClient (single connection pool).
 *
 * Tiers:
 *   api     — Official REST API, requires API key (OpenAI, Anthropic, etc.)
 *   proxy   — Web session via WebView cookie extraction (Phase 3)
 *   browser — Headless WebView JS injection, same-origin fetch (Phase 4)
 */
class AdapterRegistry(
    client: OkHttpClient,
    browserManager: BrowserProviderManager,
) {

    private val adapters: Map<String, ProviderAdapter> = mapOf(
        // ── API tier ──────────────────────────────────────────────────────────
        "openai-api"       to OpenAIAdapter(client),
        "anthropic-api"    to AnthropicAdapter(client),
        "gemini-api"       to GeminiAdapter(client),
        "openrouter-api"   to OpenRouterAdapter(client),
        "ollama-local"     to OllamaAdapter(client),
        // ── Proxy tier ────────────────────────────────────────────────────────
        "chatgpt-proxy"    to ChatGPTProxyAdapter(client),
        "claude-proxy"     to ClaudeProxyAdapter(client),
        // ── Browser tier ──────────────────────────────────────────────────────
        "chatgpt-browser"  to BrowserTierAdapter("chatgpt-browser", browserManager),
        "claude-browser"   to BrowserTierAdapter("claude-browser", browserManager),
    )

    /** Returns the adapter for [providerId], or null if not registered. */
    fun get(providerId: String): ProviderAdapter? = adapters[providerId]

    /** Returns all registered adapter IDs. */
    fun providerIds(): Set<String> = adapters.keys

    /** True if the adapter for this provider is a proxy-tier (WebView login) adapter. */
    fun isProxy(providerId: String): Boolean = adapters[providerId] is ProxyProviderAdapter

    /** True if the adapter for this provider is a browser-tier (JS injection) adapter. */
    fun isBrowser(providerId: String): Boolean = adapters[providerId] is BrowserTierAdapter

    /** Convenience: access the Ollama adapter directly for model-list operations. */
    val ollama: OllamaAdapter get() = adapters["ollama-local"] as OllamaAdapter
}
