package com.forge.bridge.data.remote.providers

import com.forge.bridge.data.model.GenerationChunk
import com.forge.bridge.data.model.GenerationRequest
import kotlinx.coroutines.flow.Flow

interface BaseProvider {
    val id: String
    val type: String

    fun generate(request: GenerationRequest, token: String): Flow<GenerationChunk>
}
