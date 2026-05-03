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
private const val PAGE_LOAD_TIMEOUT_S = 30L

class BrowserProviderManager(
    private val context: Context,
    private val vault: VaultManager,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class Entry(
        val webView: WebView,
        val bridge: JavaScriptBridge,
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

        val entry = ensureLoaded(providerId, config)

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

        mainHandler.post {
            entry.webView.evaluateJavascript(script, null)
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
        entries[providerId]?.let { return it }

        val loaded = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val bridge = JavaScriptBridge(providerId)

        mainHandler.post {
            restoreCookies(config.cookieDomain, config.vaultSessionKey)
            val webView = WebView(context.applicationContext).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    userAgentString = DESKTOP_UA
                }
                addJavascriptInterface(bridge, "ForgeBridge")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        Log.d(TAG, "[$providerId] page loaded: $url")
                        loaded.countDown()
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest?, error: WebResourceError?) {
                        Log.w(TAG, "[$providerId] page error: ${error?.description}")
                        loaded.countDown()
                    }
                }
                loadUrl(config.homeUrl)
            }
            entries[providerId] = Entry(webView, bridge)
            ready.countDown()
        }

        ready.await(PAGE_LOAD_TIMEOUT_S, TimeUnit.SECONDS)
        loaded.await(PAGE_LOAD_TIMEOUT_S, TimeUnit.SECONDS)
        return entries[providerId]!!
    }

    private fun restoreCookies(domain: String, vaultKey: String) {
        val cookieString = vault.getSessionToken(vaultKey) ?: return
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cookieString.split("\n").forEach { line ->
            val parts = line.split("\t", limit = 7)
            if (parts.size >= 7) {
                val name = parts[5]
                val value = parts[6]
                cm.setCookie(domain, "$name=$value")
            } else if (line.contains("=")) {
                cm.setCookie(domain, line.trim())
            }
        }
        cm.flush()
        Log.d(TAG, "Restored cookies for $domain from vault ($vaultKey)")
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
        .replace("`", "\\`")

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"
    }
}
