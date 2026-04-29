package com.forge.bridge.data.remote.providers.chatgpt

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

class ChatGptProxyAdapter @Inject constructor(
    private val client: HttpClient,
    private val json: Json
) : BaseProvider {
    override val id: String = "chatgpt-proxy"
    override val type: String = "chatgpt-proxy"

    override fun generate(request: GenerationRequest, token: String): Flow<GenerationChunk> = flow {
        try {
            // 1. Get Access Token using Session Token
            val accessToken = refreshAccessToken(token)
                ?: throw Exception("Failed to refresh ChatGPT access token")

            // 2. Call Unofficial Conversation API
            client.sse(
                urlString = "https://chatgpt.com/backend-api/conversation",
                setup = {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "text/event-stream")
                    contentType(ContentType.Application.Json)
                    setBody(buildChatGptRequest(request))
                }
            ) {
                incoming.collect { event ->
                    val data = event.data ?: return@collect
                    if (data == "[DONE]") {
                        emit(GenerationChunk(type = "finish", provider = "ChatGPT (Proxy)"))
                        return@collect
                    }
                    val content = parseChatGptChunk(data)
                    if (content != null) {
                        emit(GenerationChunk(type = "content", chunk = content, provider = "ChatGPT (Proxy)"))
                    }
                }
            }
        } catch (e: Exception) {
            emit(GenerationChunk(type = "error", error = e.message))
        }
    }

    suspend fun refreshAccessToken(sessionToken: String): String? {
        return try {
            val response: String = client.get("https://chatgpt.com/api/auth/session") {
                header("Cookie", "__Secure-next-auth.session-token=$sessionToken")
            }.body()
            json.parseToJsonElement(response).jsonObject["accessToken"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }
    }

    private fun buildChatGptRequest(request: GenerationRequest): JsonObject {
        val messages = request.messages.map { msg ->
            buildJsonObject {
                put("id", UUID.randomUUID().toString())
                put("author", buildJsonObject { put("role", msg.role) })
                put("content", buildJsonObject { 
                    put("content_type", "text")
                    putJsonArray("parts") { add(msg.content) }
                })
            }
        }

        return buildJsonObject {
            put("action", "next")
            putJsonArray("messages") { messages.forEach { add(it) } }
            put("parent_message_id", UUID.randomUUID().toString())
            put("model", "auto")
            put("timezone_offset_min", -60)
            put("history_and_training_disabled", false)
        }
    }

    private fun parseChatGptChunk(data: String): String? {
        return try {
            val element = json.parseToJsonElement(data)
            val parts = element.jsonObject["message"]?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
            parts?.get(0)?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }
    }
}
