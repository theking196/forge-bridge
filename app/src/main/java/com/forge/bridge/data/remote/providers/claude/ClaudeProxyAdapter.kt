package com.forge.bridge.data.remote.providers.claude

import com.forge.bridge.data.model.GenerationChunk
import com.forge.bridge.data.model.GenerationRequest
import com.forge.bridge.data.remote.providers.BaseProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import java.util.UUID
import javax.inject.Inject

class ClaudeProxyAdapter @Inject constructor(
    private val client: HttpClient,
    private val json: Json
) : BaseProvider {
    override val id: String = "claude-proxy"
    override val type: String = "claude-proxy"

    override fun generate(request: GenerationRequest, token: String): Flow<GenerationChunk> = flow {
        try {
            // 1. Get Organization UUID
            val orgUuid = getOrgUuid(token) 
                ?: throw Exception("Failed to find Claude organization")

            // 2. Start/Continue Conversation
            client.sse(
                urlString = "https://claude.ai/api/organizations/$orgUuid/chat_conversations",
                setup = {
                    header("Cookie", "sessionKey=$token")
                    header("Accept", "text/event-stream")
                    contentType(ContentType.Application.Json)
                    setBody(buildClaudeProxyRequest(request))
                }
            ) {
                incoming.collect { event ->
                    val data = event.data ?: return@collect
                    if (data.contains("completion")) {
                        val content = parseClaudeChunk(data)
                        if (content != null) {
                            emit(GenerationChunk(type = "content", chunk = content, provider = "Claude (Proxy)"))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            emit(GenerationChunk(type = "error", error = e.message))
        }
    }

    private suspend fun getOrgUuid(sessionKey: String): String? {
        return try {
            val response: String = client.get("https://claude.ai/api/organizations") {
                header("Cookie", "sessionKey=$sessionKey")
            }.body()
            val jsonArray = json.parseToJsonElement(response).jsonArray
            jsonArray[0].jsonObject["uuid"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }
    }

    private fun buildClaudeProxyRequest(request: GenerationRequest): JsonObject {
        return buildJsonObject {
            put("prompt", request.messages.lastOrNull()?.content ?: "")
            put("timezone", "America/New_York")
            put("model", "claude-3-sonnet-20240229") // Fallback model for web
        }
    }

    private fun parseClaudeChunk(data: String): String? {
        return try {
            val element = json.parseToJsonElement(data)
            element.jsonObject["completion"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }
    }
}
