package com.forge.bridge.data.remote.providers.openai

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
import io.ktor.sse.sse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import com.forge.bridge.data.repository.FileRepository
import javax.inject.Inject

class OpenAiAdapter @Inject constructor(
    private val client: HttpClient,
    private val json: Json,
    private val fileRepo: FileRepository
) : BaseProvider {
    override val id: String = "openai-api"
    override val type: String = "openai"

    override fun generate(request: GenerationRequest, token: String): Flow<GenerationChunk> = flow {
        // OpenAI Chat Completion SSE Flow
        try {
            client.sse(
                urlString = "https://api.openai.com/v1/chat/completions",
                setup = {
                    header("Authorization", "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(buildOpenAiRequest(request))
                }
            ) {
                incoming.collect { event ->
                    if (event.data == "[DONE]") {
                        emit(GenerationChunk(type = "finish", provider = "OpenAI", model = request.model))
                        return@collect
                    }
                    val data = event.data ?: return@collect
                    val content = parseOpenAiChunk(data)
                    if (content != null) {
                        emit(GenerationChunk(type = "content", chunk = content, provider = "OpenAI", model = request.model))
                    }
                }
            }
        } catch (e: Exception) {
            emit(GenerationChunk(type = "error", error = e.message))
        }
    }

    private fun buildOpenAiRequest(request: GenerationRequest): JsonObject {
        return buildJsonObject {
            put("model", request.model)
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
                                            put("type", "image_url")
                                            putJsonObject("image_url") {
                                                put("url", "data:$mime;base64,$base64")
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

    private fun parseOpenAiChunk(data: String): String? {
        return try {
            val element = json.parseToJsonElement(data)
            element.jsonObject["choices"]?.jsonArray?.get(0)?.jsonObject?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }
    }
}
