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
        "chatgpt-proxy" -> "document.querySelector('textarea') !== null || document.querySelector('[data-testid=\\"send-button\\"]') !== null"
        "claude-proxy" -> "document.querySelector('textarea') !== null || document.querySelector('button[aria-label*=\\"Send\\"]') !== null"
        else -> "false"
    }

    private fun isChatGPTLoggedIn(url: String): Boolean {
        return url.startsWith("https://chatgpt.com/") &&
                !url.contains("/auth/") &&
                !url.contains("/login") &&
                !url.contains("/signup")
    }

    private fun isClaudeLoggedIn(url: String): Boolean {
        return url.startsWith("https://claude.ai/new") ||
                url.startsWith("https://claude.ai/chat") ||
                url == "https://claude.ai/"
    }

    private fun onLoginSuccess() {
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        Toast.makeText(this, "Login detected — saving session…", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            CookieManager.getInstance().flush()
            val cookies = CookieManager.getInstance().getCookie(cookieDomain) ?: ""

            if (cookies.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@WebLoginActivity, "Could not extract cookies — try again", Toast.LENGTH_LONG).show()
                    loginDetected = false
                    binding.progressBar.visibility = View.GONE
                }
                return@launch
            }

            val extraToken = when (providerId) {
                "chatgpt-proxy" -> fetchChatGPTAccessToken(cookies)
                "claude-proxy" -> fetchClaudeOrgId(cookies)
                else -> null
            }

            vault.storeSessionToken(providerId, cookies)
            if (extraToken != null) vault.storeApiKey(providerId, extraToken)
            db.updateProviderStatus(providerId, "connected", System.currentTimeMillis())

            val browserCounterpart = when (providerId) {
                "chatgpt-proxy" -> "chatgpt-browser"
                "claude-proxy" -> "claude-browser"
                else -> null
            }
            browserCounterpart?.let { db.updateProviderStatus(it, "connected", System.currentTimeMillis()) }

            withContext(Dispatchers.Main) {
                setResult(RESULT_OK)
                finish()
            }
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
