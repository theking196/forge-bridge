package com.forge.bridge.data.remote.browser

import android.util.Log
import android.webkit.JavascriptInterface
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "JSBridge"

/**
 * Exposed to JavaScript as `window.ForgeBridge`.
 * JS injectors call these methods when chunks arrive from the provider's API.
 *
 * All @JavascriptInterface methods run on a background thread (the WebView's
 * JS thread). Thread safety is handled via [AtomicReference] and [LinkedBlockingQueue].
 *
 * Lifecycle: one instance per in-flight request. After [onDone] or [onError],
 * the instance should be discarded.
 */
class JavaScriptBridge(private val providerId: String) {

    // The current active queue — set before JS is injected, read by the SSE thread
    private val queueRef = AtomicReference<LinkedBlockingQueue<BrowserChunk>?>(null)

    /** Called by JS to deliver a response text delta. */
    @JavascriptInterface
    fun onChunk(text: String) {
        Log.v(TAG, "[$providerId] chunk: ${text.take(40)}")
        queueRef.get()?.offer(BrowserChunk.text(text))
    }

    /** Called by JS when the full response is complete. */
    @JavascriptInterface
    fun onDone() {
        Log.d(TAG, "[$providerId] done")
        queueRef.get()?.offer(BrowserChunk.DONE)
    }

    /** Called by JS on error (session expired, network failure, etc.). */
    @JavascriptInterface
    fun onError(message: String) {
        Log.w(TAG, "[$providerId] error: $message")
        queueRef.get()?.offer(BrowserChunk.error(message))
    }

    /** Called by JS to log debug messages (only in debug builds). */
    @JavascriptInterface
    fun log(message: String) {
        Log.d(TAG, "[$providerId] JS: $message")
    }

    /**
     * Attaches a new queue for the next in-flight request.
     * Returns the queue so the SSE thread can poll it.
     */
    fun attachQueue(): LinkedBlockingQueue<BrowserChunk> {
        val q = LinkedBlockingQueue<BrowserChunk>(512)
        queueRef.set(q)
        return q
    }

    /** Detaches the queue after a request is complete or timed out. */
    fun detachQueue() { queueRef.set(null) }
}
