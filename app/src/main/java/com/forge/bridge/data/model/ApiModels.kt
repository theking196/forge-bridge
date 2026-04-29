package com.forge.bridge.data.model

import kotlinx.serialization.Serializable

@Serializable
data class GenerationRequest(
    val provider: String,
    val model: String,
    val messages: List<Message>,
    val stream: Boolean = true,
    val systemPrompt: String? = null,
    val temperature: Float = 0.7f
)

@Serializable
data class Message(
    val role: String,
    val content: String,
    val files: List<String>? = null
)

@Serializable
data class GenerationChunk(
    val type: String, // content, status, error, finish
    val chunk: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val error: String? = null
)
