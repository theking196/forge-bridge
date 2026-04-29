package com.forge.bridge.data.remote.providers.anthropic

import com.forge.bridge.data.model.GenerationChunk
import com.forge.bridge.data.model.GenerationRequest
import com.forge.bridge.data.remote.providers.BaseProvider
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import com.forge.bridge.data.repository.FileRepository
import javax.inject.Inject

class AnthropicAdapter @Inject constructor(
    private val client: HttpClient,
    private val json: Json,
    private val fileRepo: FileRepository
) : BaseProvider {
    override val id: String = "anthropic-api"
    override val type: String = "anthropic"

    override fun generate(request: GenerationRequest, token: String): Flow<GenerationChunk> = flow {
        try {
            client.sse(
                urlString = "https://api.anthropic.com/v1/messages",
                setup = {
                    header("x-api-key", token)
                    header("anthropic-version", "2023-06-01")
                    contentType(ContentType.Application.Json)
                    setBody(buildAnthropicRequest(request))
                }
            ) {
                incoming.collect { event ->
                    // Anthropic uses 'content_block_delta' for chunks
                    if (event.event == "content_block_delta") {
                        val data = event.data ?: return@collect
                        val content = parseAnthropicChunk(data)
                        if (content != null) {
                            emit(GenerationChunk(type = "content", chunk = content, provider = "Anthropic", model = request.model))
                        }
                    } else if (event.event == "message_stop") {
                        emit(GenerationChunk(type = "finish", provider = "Anthropic", model = request.model))
                    }
                }
            }
        } catch (e: Exception) {
            emit(GenerationChunk(type = "error", error = e.message))
        }
    }

    private fun buildAnthropicRequest(request: GenerationRequest): JsonObject {
        return buildJsonObject {
            put("model", request.model)
            put("max_tokens", 4096)
            put("stream", true)
            put("temperature", request.temperature)
            
            putJsonArray("messages") {
                request.messages.forEach { msg ->
                    addJsonObject {
                        put("role", msg.role)
                        
                        if (msg.files.isNullOrEmpty()) {
                            put("content", msg.content)
                        } else {
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", msg.content)
                                }
                                msg.files.forEach { file ->
                                    val base64 = fileRepo.getFileBase64(file)
                                    val mime = fileRepo.getMimeType(file)
                                    if (base64 != null) {
                                        addJsonObject {
                                            put("type", "image")
                                            putJsonObject("source") {
                                                put("type", "base64")
                                                put("media_type", mime)
                                                put("data", base64)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun parseAnthropicChunk(data: String): String? {
        return try {
            val element = json.parseToJsonElement(data)
            element.jsonObject["delta"]?.jsonObject?.get("text")?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }
    }
}
