package com.forge.bridge.data.remote.browser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.forge.bridge.data.local.VaultManager
import com.forge.bridge.data.model.GenerateRequest
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private const val TAG = "BrowserMgr"
private const val PAGE_LOAD_TIMEOUT_S = 60L
private const val INJECTOR_DELAY_MS = 1000L

class BrowserProviderManager(
    private val context: Context,
    private val vault: VaultManager,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class Entry(
        val webView: WebView,
        val bridge: JavaScriptBridge,
        var lastUsedUrl: String = "",
    )

    private val entries = mutableMapOf<String, Entry>()

    private data class ProviderConfig(
        val homeUrl: String,
        val cookieDomain: String,
        val vaultSessionKey: String,
        val injectorAsset: String,
    )

    private val configs = mapOf(
        "chatgpt-browser" to ProviderConfig(
            homeUrl = "https://chatgpt.com/",
            cookieDomain = "https://chatgpt.com",
            vaultSessionKey = "chatgpt-proxy",
            injectorAsset = "chatgpt_injector.js",
        ),
        "claude-browser" to ProviderConfig(
            homeUrl = "https://claude.ai/new",
            cookieDomain = "https://claude.ai",
            vaultSessionKey = "claude-proxy",
            injectorAsset = "claude_injector.js",
        ),
    )

    fun submitRequest(
        providerId: String,
        request: GenerateRequest,
    ): LinkedBlockingQueue<BrowserChunk> {
        val config = configs[providerId]
            ?: return errorQueue("No config for: $providerId")

        // Check if we have session cookies
        val sessionCookies = vault.getSessionToken(config.vaultSessionKey)
        if (sessionCookies.isNullOrBlank()) {
            Log.w(TAG, "[$providerId] No session cookies found - proxy login required")
            return errorQueue("Not logged in — login via Manage Providers → ${providerId.replace("-browser", "-proxy")}")
        }

        val entry = try {
            ensureLoaded(providerId, config)
        } catch (e: Exception) {
            Log.e(TAG, "[$providerId] Failed to initialize WebView", e)
            return errorQueue("WebView init failed: ${e.message}")
        }

        val injectorJs = try {
            context.assets.open("injectors/${config.injectorAsset}")
                .bufferedReader().readText()
        } catch (e: IOException) {
            Log.e(TAG, "Missing injector asset: ${config.injectorAsset}", e)
            return errorQueue("Injector script not found — reinstall app")
        }

        val userMessage = request.messages.lastOrNull { it.role == "user" }?.content
            ?: request.messages.lastOrNull()?.content
            ?: return errorQueue("No user message in request")

        val queue = entry.bridge.attachQueue()

        val script = injectorJs
            .replace("%MESSAGE%", escapeForJs(userMessage))
            .replace("%MODEL%", escapeForJs(request.model ?: ""))

        Log.d(TAG, "[$providerId] Injecting script for message (${userMessage.length} chars)")

        mainHandler.post {
            try {
                entry.webView.evaluateJavascript(script) { result ->
                    Log.d(TAG, "[$providerId] Script injection result: ${result?.take(50)}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[$providerId] Script injection failed", e)
                queue.offer(BrowserChunk.error("Script injection failed: ${e.message}"))
            }
        }

        return queue
    }

    fun destroy() {
        mainHandler.post {
            entries.values.forEach { it.webView.destroy() }
            entries.clear()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureLoaded(providerId: String, config: ProviderConfig): Entry {
        entries[providerId]?.let { 
            Log.d(TAG, "[$providerId] Reusing existing WebView")
            return it 
        }

        Log.d(TAG, "[$providerId] Creating new WebView")
        
        val loaded = CountDownLatch(1)
        val ready = CountDownLatch(1)
        var loadError: String? = null
        val bridge = JavaScriptBridge(providerId)

        mainHandler.post {
            try {
                restoreCookies(config.cookieDomain, config.vaultSessionKey)
                
                val webView = WebView(context.applicationContext).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        userAgentString = DESKTOP_UA
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        blockNetworkImage = false
                        loadsImagesAutomatically = true
                    }
                    addJavascriptInterface(bridge, "ForgeBridge")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            Log.d(TAG, "[$providerId] page loaded: $url")
                            entries[providerId]?.lastUsedUrl = url
                            loaded.countDown()
                        }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest?, error: WebResourceError?) {
                            val errMsg = error?.description?.toString() ?: "Unknown error"
                            Log.w(TAG, "[$providerId] page error: $errMsg")
                            loadError = errMsg
                            loaded.countDown()
                        }
                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse) {
                            Log.w(TAG, "[$providerId] HTTP error: ${errorResponse.statusCode}")
                        }
                    }
                    Log.d(TAG, "[$providerId] Loading URL: ${config.homeUrl}")
                    loadUrl(config.homeUrl)
                }
                entries[providerId] = Entry(webView, bridge, config.homeUrl)
                ready.countDown()
            } catch (e: Exception) {
                Log.e(TAG, "[$providerId] WebView creation failed", e)
                loadError = e.message
                ready.countDown()
                loaded.countDown()
            }
        }

        ready.await(5, TimeUnit.SECONDS)
        loaded.await(PAGE_LOAD_TIMEOUT_S, TimeUnit.SECONDS)
        
        loadError?.let {
            throw IOException("Page load failed: $it")
        }
        
        return entries[providerId] ?: throw IOException("WebView not initialized")
    }

    private fun restoreCookies(domain: String, vaultKey: String) {
        val cookieString = vault.getSessionToken(vaultKey)
        if (cookieString.isNullOrBlank()) {
            Log.w(TAG, "No session cookies found for $vaultKey")
            return
        }
        
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(null, true)
        
        // Clear existing cookies first to avoid conflicts
        cm.removeSessionCookies(null)
        cm.flush()
        
        var cookieCount = 0
        
        // Try parsing as Netscape cookie format (tab-separated)
        cookieString.split("\n").forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank()) return@forEach
            
            val parts = trimmed.split("\t", limit = 7)
            if (parts.size >= 7) {
                // Netscape format: domain, flag, path, secure, expiry, name, value
                val name = parts[5]
                val value = parts[6]
                val cookieLine = "$name=$value"
                cm.setCookie(domain, cookieLine)
                cookieCount++
            } else if (trimmed.contains("=")) {
                // Simple name=value format
                cm.setCookie(domain, trimmed)
                cookieCount++
            }
        }
        
        cm.flush()
        Log.d(TAG, "Restored $cookieCount cookies for $domain from vault ($vaultKey)")
    }

    private fun errorQueue(msg: String): LinkedBlockingQueue<BrowserChunk> {
        val q = LinkedBlockingQueue<BrowserChunk>(2)
        q.offer(BrowserChunk.error(msg))
        return q
    }

    private fun escapeForJs(s: String): String = s
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
        .replace("`", "\\`")

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"
    }
}
