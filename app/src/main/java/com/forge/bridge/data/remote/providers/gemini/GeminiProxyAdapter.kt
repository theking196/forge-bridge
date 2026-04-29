package com.forge.bridge.data.remote.providers.gemini

import com.forge.bridge.data.model.GenerationChunk
import com.forge.bridge.data.model.GenerationRequest
import com.forge.bridge.data.remote.providers.BaseProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import javax.inject.Inject

class GeminiProxyAdapter @Inject constructor(
    private val client: HttpClient,
    private val json: Json
) : BaseProvider {
    override val id: String = "gemini-proxy"
    override val type: String = "gemini-proxy"

    override fun generate(request: GenerationRequest, token: String): Flow<GenerationChunk> = flow {
        try {
            // Gemini Proxy is intentionally blocked in this Tier.
            // Google's internal APIs use extremely complex multi-part proto/JSON format.
            // Additionally, it is enforced by DBSC (Device Bound Session Credentials).
            emit(GenerationChunk(type = "status", chunk = "Connecting to Gemini Web Session..."))
            
            throw Exception("Gemini Tier 2 (Proxy) is currently blocked by Google DBSC. Use Tier 1 (Official API) or Tier 3 (Browser Automation) instead.")
        } catch (e: Exception) {
            emit(GenerationChunk(type = "error", error = e.message))
        }
    }
}
