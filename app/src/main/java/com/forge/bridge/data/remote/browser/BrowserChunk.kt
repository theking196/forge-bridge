package com.forge.bridge.data.remote.browser

/**
 * A single item in the chunk queue that flows from the JavaScript bridge
 * to the SSE output stream.
 *
 * [content] → non-null text delta to write to the stream
 * [error]   → non-null error message, signals stream failure
 * [done]    → true means the response is complete (no more chunks)
 */
data class BrowserChunk(
    val content: String? = null,
    val error: String? = null,
    val done: Boolean = false,
) {
    companion object {
        fun text(chunk: String) = BrowserChunk(content = chunk)
        fun error(msg: String) = BrowserChunk(error = msg, done = true)
        val DONE = BrowserChunk(done = true)
    }
}
