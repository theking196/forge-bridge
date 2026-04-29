package com.forge.bridge.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ProxyAuthActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url") ?: "https://chatgpt.com"
        val providerType = intent.getStringExtra("type") ?: "chatgpt-proxy"

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AuthWebView(url, providerType) { token ->
                        // Return result to calling activity
                        intent.putExtra("token", token)
                        setResult(RESULT_OK, intent)
                        finish()
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AuthWebView(url: String, providerType: String, onTokenCaptured: (String) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val cookies = CookieManager.getInstance().getCookie(url) ?: ""
                        
                        if (providerType == "chatgpt-proxy" && cookies.contains("__Secure-next-auth.session-token")) {
                            val token = cookies.split(";")
                                .find { it.trim().startsWith("__Secure-next-auth.session-token=") }
                                ?.split("=")?.get(1)
                            if (token != null) onTokenCaptured(token)
                        } else if (providerType == "claude-proxy" && cookies.contains("sessionKey")) {
                            val token = cookies.split(";")
                                .find { it.trim().startsWith("sessionKey=") }
                                ?.split("=")?.get(1)
                            if (token != null) onTokenCaptured(token)
                        } else if (providerType == "gemini-proxy" && cookies.contains("__Secure-1PSID")) {
                            // For Gemini, we might need multiple cookies, but we'll start with 1PSID
                            val token = cookies.split(";")
                                .find { it.trim().startsWith("__Secure-1PSID=") }
                                ?.split("=")?.get(1)
                            if (token != null) onTokenCaptured(token)
                        }
                    }
                }
                loadUrl(url)
            }
        }
    )
}
