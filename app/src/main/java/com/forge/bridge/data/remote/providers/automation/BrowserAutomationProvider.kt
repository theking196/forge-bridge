package com.forge.bridge.data.remote.providers.automation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.forge.bridge.data.model.GenerationChunk
import com.forge.bridge.data.model.GenerationRequest
import com.forge.bridge.data.remote.providers.BaseProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BrowserAutomationProvider @Inject constructor(
    private val context: Context
) : BaseProvider {
    override val id: String = "browser-tier"
    override val type: String = "browser-tier"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var webView: WebView? = null
    private val isLoaded = AtomicBoolean(false)
    private var currentUrl: String = ""

    private fun ensureWebView(url: String) {
        Handler(Looper.getMainLooper()).post {
            if (webView == null) {
                webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            isLoaded.set(true)
                        }
                    }
                }
            }
            if (currentUrl != url) {
                isLoaded.set(false)
                currentUrl = url
                webView?.loadUrl(url)
            }
        }
    }

    override fun generate(request: GenerationRequest, token: String): Flow<GenerationChunk> = callbackFlow {
        val targetUrl = if (token.startsWith("http")) token else "https://chatgpt.com"
        ensureWebView(targetUrl)

        // Wait for page load if necessary (simplified polling for this version)
        scope.launch {
            while (!isLoaded.get()) { delay(500) }
            
            Handler(Looper.getMainLooper()).post {
                val script = buildAutomationScript(request)
                
                webView?.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onContent(content: String) {
                        trySend(GenerationChunk(type = "content", chunk = content, provider = "Browser Tier"))
                    }
                    @JavascriptInterface
                    fun onFinish() {
                        trySend(GenerationChunk(type = "finish", provider = "Browser Tier"))
                    }
                    @JavascriptInterface
                    fun onError(error: String) {
                        trySend(GenerationChunk(type = "error", error = error))
                    }
                }, "AutomationBridge")

                webView?.evaluateJavascript(script, null)
            }
        }
        awaitClose { /* In a real app, remove JS interface and stop observer */ }
    }

    private fun buildAutomationScript(request: GenerationRequest): String {
        val prompt = request.messages.last().content.replace("'", "\\'")
        return """
            (function() {
                const strategies = {
                    'chatgpt.com': {
                        input: '#prompt-textarea',
                        send: 'button[data-testid="send-button"]',
                        stop: 'button[aria-label="Stop generating"]',
                        output: '.agent-turn .prose'
                    },
                    'claude.ai': {
                        input: 'div[contenteditable="true"]',
                        send: 'button[aria-label*="Send Message"], button[data-testid="send-button"]',
                        stop: 'button[aria-label*="Stop"]',
                        output: '.font-claude-message, div[data-testid="message-content"]'
                    },
                    'gemini.google.com': {
                        input: 'div[role="textbox"]',
                        send: 'button[aria-label*="Send"]',
                        stop: 'button[aria-label="Stop"]',
                        output: 'message-content, .message-content-container'
                    }
                };

                const domain = window.location.hostname;
                const strategy = Object.entries(strategies).find(([k]) => domain.includes(k))?.[1] || strategies['chatgpt.com'];

                const inputEl = document.querySelector(strategy.input);
                if (!inputEl) {
                    window.AutomationBridge.onError('Input element not found for ' + domain);
                    return;
                }

                // Inject content
                if (inputEl.tagName === 'DIV' || inputEl.getAttribute('contenteditable') === 'true') {
                    inputEl.focus();
                    document.execCommand('insertText', false, '$prompt');
                } else {
                    inputEl.value = '$prompt';
                }
                inputEl.dispatchEvent(new Event('input', { bubbles: true }));

                // Click Send
                setTimeout(() => {
                    const sendBtn = document.querySelector(strategy.send);
                    if (sendBtn) sendBtn.click();
                }, 500);

                // Observe Response
                let lastLen = 0;
                let isFinished = false;
                const observer = new MutationObserver(() => {
                    if (isFinished) return;
                    
                    const messages = document.querySelectorAll(strategy.output);
                    const lastMsg = messages[messages.length - 1];
                    if (lastMsg) {
                        const text = lastMsg.innerText;
                        if (text.length > lastLen) {
                            window.AutomationBridge.onContent(text.substring(lastLen));
                            lastLen = text.length;
                        }
                    }
                    
                    // Completion check: if 'Stop' button disappears
                    const stopBtn = document.querySelector(strategy.stop);
                    const isTyping = document.querySelector('.streaming, .result-streaming, .typing');
                    
                    if (!stopBtn && !isTyping && lastLen > 0) {
                        isFinished = true;
                        window.AutomationBridge.onFinish();
                    }
                });

                observer.observe(document.body, { childList: true, subtree: true });
            })();
        """.trimIndent()
    }
}
