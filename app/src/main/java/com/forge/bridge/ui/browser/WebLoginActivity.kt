package com.forge.bridge.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.forge.bridge.ForgeBridgeApp
import com.forge.bridge.databinding.ActivityWebLoginBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "WebLoginActivity"

class WebLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebLoginBinding

    private val providerId get() = intent.getStringExtra(EXTRA_PROVIDER_ID)!!
    private val loginUrl get() = intent.getStringExtra(EXTRA_LOGIN_URL)!!
    private val cookieDomain get() = intent.getStringExtra(EXTRA_COOKIE_DOMAIN)!!

    private val vault get() = (application as ForgeBridgeApp).container.vaultManager
    private val db get() = (application as ForgeBridgeApp).container.database
    private val httpClient get() = (application as ForgeBridgeApp).container.httpClient

    private var loginDetected = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "Sign in — ${providerDisplayName()}"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        CookieManager.getInstance().apply {
            removeAllCookies(null)
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.webView, true)
            flush()
        }

        binding.webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                userAgentString = DESKTOP_USER_AGENT
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    binding.progressBar.visibility = View.GONE
                    if (!loginDetected) checkLoginSuccess(view, url)
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!loginDetected) checkLoginSuccess(view, request.url.toString())
                    return false
                }
            }

            loadUrl(loginUrl)
        }

        binding.btnCancelLogin.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        setResult(RESULT_CANCELED)
        finish()
        return true
    }

    private fun checkLoginSuccess(view: WebView, url: String) {
        val loggedInUrl = when (providerId) {
            "chatgpt-proxy" -> isChatGPTLoggedIn(url)
            "claude-proxy" -> isClaudeLoggedIn(url)
            else -> false
        }
        if (!loggedInUrl) return
        view.evaluateJavascript(getLoginCheckJs()) { value ->
            val domReady = value == "true"
            if (domReady && !loginDetected) {
                loginDetected = true
                onLoginSuccess()
            }
        }
    }

    private fun getLoginCheckJs(): String = when (providerId) {
        // Identifier-only attribute selectors avoid nested-quote escaping for evaluateJavascript.
        "chatgpt-proxy" ->
            "document.querySelector('textarea') !== null || document.querySelector('[data-testid=send-button]') !== null"
        // Claude composer is often contenteditable; aria-label selector must quote the substring (CSS).
        "claude-proxy" ->
            "(function(){var t=document.querySelector('textarea');if(t)return true;" +
                "var ce=document.querySelector('[contenteditable=\"true\"]');" +
                "if(ce)return true;" +
                "if(document.querySelector('button[aria-label*=\"Send\"]'))return true;" +
                "if(document.querySelector('[data-testid=\"send-button\"]'))return true;" +
                "return document.querySelector('fieldset button[type=\"submit\"]')!=null;})()"
        else -> "false"
    }

    private fun isChatGPTLoggedIn(url: String): Boolean {
        return url.startsWith("https://chatgpt.com/") &&
                !url.contains("/auth/") &&
                !url.contains("/login") &&
                !url.contains("/signup")
    }

    private fun isClaudeLoggedIn(url: String): Boolean {
        if (!url.startsWith("https://claude.ai/") && !url.startsWith("https://www.claude.ai/")) return false
        val u = url.lowercase()
        if ("/login" in u || "/authorize" in u || "/sso" in u || "claude.ai/login" in u) return false
        // Any other claude.ai path is treated as the signed-in web app (chat, new, project, etc.).
        return true
    }

    private fun onLoginSuccess() {
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        Toast.makeText(this, "Login detected — saving session…", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            CookieManager.getInstance().flush()
            Thread.sleep(500) // Give cookies time to persist
            
            var cookies = CookieManager.getInstance().getCookie(cookieDomain) ?: ""
            // WebView may associate cookies with www vs apex — try both for Claude/ChatGPT.
            if (cookies.isEmpty() && providerId == "claude-proxy") {
                cookies = CookieManager.getInstance().getCookie("https://www.claude.ai") ?: ""
            }
            if (cookies.isEmpty() && providerId == "chatgpt-proxy") {
                cookies = CookieManager.getInstance().getCookie("https://www.chatgpt.com") ?: ""
            }

            if (cookies.isEmpty()) {
                // Try alternative cookie extraction via JavaScript
                withContext(Dispatchers.Main) {
                    binding.webView.evaluateJavascript("document.cookie") { cookieStr ->
                        // cookieStr is JSON-encoded, unwrap quotes
                        val rawCookies = cookieStr.trim('"').replace("\\u003D", "=").replace("\\u003B", ";")
                        if (rawCookies.isNotBlank() && rawCookies != "null") {
                            lifecycleScope.launch(Dispatchers.IO) {
                                processLoginSuccess(rawCookies)
                            }
                        } else {
                            Toast.makeText(this@WebLoginActivity, "Could not extract cookies — try again", Toast.LENGTH_LONG).show()
                            loginDetected = false
                            binding.progressBar.visibility = View.GONE
                        }
                    }
                }
            } else {
                processLoginSuccess(cookies)
            }
        }
    }
    
    private suspend fun processLoginSuccess(cookies: String) = withContext(Dispatchers.IO) {
        if (cookies.isEmpty()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@WebLoginActivity, "Could not extract cookies — try again", Toast.LENGTH_LONG).show()
                loginDetected = false
                binding.progressBar.visibility = View.GONE
            }
            return@withContext
        }

        Log.d(TAG, "[$providerId] Extracted cookies (${cookies.length} chars)")
        
        val extraToken = when (providerId) {
            "chatgpt-proxy" -> fetchChatGPTAccessToken(cookies)
            "claude-proxy" -> fetchClaudeOrgId(cookies)
            else -> null
        }

        vault.storeSessionToken(providerId, cookies)
        if (extraToken != null) {
            Log.d(TAG, "[$providerId] Stored extra token: ${extraToken.take(20)}...")
            vault.storeApiKey(providerId, extraToken)
        } else {
            Log.w(TAG, "[$providerId] Could not fetch extra token")
        }
        db.updateProviderStatus(providerId, "connected", System.currentTimeMillis())

        val browserCounterpart = when (providerId) {
            "chatgpt-proxy" -> "chatgpt-browser"
            "claude-proxy" -> "claude-browser"
            else -> null
        }
        browserCounterpart?.let { 
            db.updateProviderStatus(it, "connected", System.currentTimeMillis())
            Log.d(TAG, "[$providerId] Also activated browser tier: $it")
        }

        withContext(Dispatchers.Main) {
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun fetchChatGPTAccessToken(cookies: String): String? {
        return try {
            val req = okhttp3.Request.Builder()
                .url("https://chatgpt.com/api/auth/session")
                .header("Cookie", cookies)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val obj = com.google.gson.JsonParser.parseString(body).asJsonObject
                obj["accessToken"]?.asString
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not fetch ChatGPT access token", e)
            null
        }
    }

    private fun fetchClaudeOrgId(cookies: String): String? {
        return try {
            val req = okhttp3.Request.Builder()
                .url("https://claude.ai/api/organizations")
                .header("Cookie", cookies)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val arr = com.google.gson.JsonParser.parseString(body).asJsonArray
                arr.firstOrNull()?.asJsonObject?.get("uuid")?.asString
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not fetch Claude org ID", e)
            null
        }
    }

    private fun providerDisplayName() = when (providerId) {
        "chatgpt-proxy" -> "ChatGPT"
        "claude-proxy" -> "Claude"
        else -> providerId
    }

    companion object {
        const val EXTRA_PROVIDER_ID = "provider_id"
        const val EXTRA_LOGIN_URL = "login_url"
        const val EXTRA_COOKIE_DOMAIN = "cookie_domain"
        const val REQUEST_CODE = 1001

        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

        fun intentFor(context: Context, providerId: String, loginUrl: String, cookieDomain: String) =
            Intent(context, WebLoginActivity::class.java).apply {
                putExtra(EXTRA_PROVIDER_ID, providerId)
                putExtra(EXTRA_LOGIN_URL, loginUrl)
                putExtra(EXTRA_COOKIE_DOMAIN, cookieDomain)
            }
    }
}
