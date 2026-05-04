package com.forge.bridge.data.remote.adapters

import com.forge.bridge.data.model.GenerateRequest
import java.io.OutputStream

/**
 * Extension of [ProviderAdapter] for proxy-tier providers (ChatGPT, Claude Pro).
 * These providers authenticate via session cookies from a WebView login rather
 * than a plain API key. Both the access token and cookie string are required.
 */
interface ProxyProviderAdapter : ProviderAdapter {

    /**
     * Connectivity check that includes vault session cookies (required for real
     * chat — [ProviderAdapter.testConnection] alone is often insufficient).
     */
    fun testConnectionWithCookies(apiKey: String, cookies: String): TestResult =
        testConnection(apiKey)

    /**
     * Streaming call that also accepts the raw cookie string from CookieManager.
     * [accessToken] = Bearer token or org ID stored via VaultManager.getApiKey()
     * [cookies]     = Raw cookie string stored via VaultManager.getSessionToken()
     */
    fun streamWithCookies(
        request: GenerateRequest,
        accessToken: String,
        cookies: String,
        out: OutputStream,
    )
}
