package com.forge.bridge.data.remote.providers.gemini

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

class GeminiAdapter @Inject constructor(
    private val client: HttpClient,
    private val json: Json,
    private val fileRepo: FileRepository
) : BaseProvider {
    override val id: String = "gemini-api"
    override val type: String = "gemini"

    override fun generate(request: GenerationRequest, token: String): Flow<GenerationChunk> = flow {
        try {
            val modelId = request.model.ifEmpty { "gemini-1.5-flash" }
            client.sse(
                urlString = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:streamGenerateContent?key=$token",
                setup = {
                    contentType(ContentType.Application.Json)
                    setBody(buildGeminiRequest(request))
                }
            ) {
                incoming.collect { event ->
                    val data = event.data ?: return@collect
                    val content = parseGeminiChunk(data)
                    if (content != null) {
                        emit(GenerationChunk(type = "content", chunk = content, provider = "Gemini", model = modelId))
                    }
                }
            }
        } catch (e: Exception) {
            emit(GenerationChunk(type = "error", error = e.message))
        }
    }

    private fun buildGeminiRequest(request: GenerationRequest): JsonObject {
        return buildJsonObject {
            putJsonArray("contents") {
                request.messages.forEach { msg ->
                    addJsonObject {
                        put("role", if (msg.role == "assistant") "model" else "user")
                        putJsonArray("parts") {
                            addJsonObject { put("text", msg.content) }
                            
                            msg.files?.forEach { file ->
                                val base64 = fileRepo.getFileBase64(file)
                                val mime = fileRepo.getMimeType(file)
                                if (base64 != null) {
                                    addJsonObject {
                                        putJsonObject("inlineData") {
                                            put("mimeType", mime)
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

    private fun parseGeminiChunk(data: String): String? {
        return try {
            val element = json.parseToJsonElement(data)
            element.jsonObject["candidates"]?.jsonArray?.get(0)?.jsonObject?.get("content")
                ?.jsonObject?.get("parts")?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }
    }
}
