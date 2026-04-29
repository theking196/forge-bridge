package com.forge.bridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.bridge.data.model.Message
import com.forge.bridge.data.model.GenerationRequest
import com.forge.bridge.data.repository.GenerationRepository
import com.forge.bridge.data.local.entities.ProviderEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val generationRepository: GenerationRepository
) : ViewModel() {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating = _isGenerating.asStateFlow()

    fun sendMessage(provider: ProviderEntity, text: String) {
        val userMsg = ChatMessage(role = "user", content = text)
        _messages.value = _messages.value + userMsg
        
        _isGenerating.value = true
        
        val assistantMsg = ChatMessage(role = "assistant", content = "", isStreaming = true)
        _messages.value = _messages.value + assistantMsg
        val assistantIndex = _messages.value.lastIndex

        viewModelScope.launch {
            try {
                // Map current UI messages to API messages
                val apiMessages = _messages.value.filter { !it.isStreaming }.map { 
                    Message(role = it.role, content = it.content) 
                }

                val request = GenerationRequest(
                    provider = provider.id,
                    model = "", // Adapter handles default model
                    messages = apiMessages
                )

                generationRepository.generate(request).collect { chunk ->
                    if (chunk.type == "content" && chunk.chunk != null) {
                        updateAssistantMessage(assistantIndex, chunk.chunk)
                    }
                }
            } catch (e: Exception) {
                updateAssistantMessage(assistantIndex, "\n[Error: ${e.message}]")
            } finally {
                markAssistantFinished(assistantIndex)
                _isGenerating.value = false
            }
        }
    }

    private fun updateAssistantMessage(index: Int, chunk: String) {
        val currentList = _messages.value.toMutableList()
        if (index < currentList.size) {
            val oldMsg = currentList[index]
            currentList[index] = oldMsg.copy(content = oldMsg.content + chunk)
            _messages.value = currentList
        }
    }

    private fun markAssistantFinished(index: Int) {
        val currentList = _messages.value.toMutableList()
        if (index < currentList.size) {
            currentList[index] = currentList[index].copy(isStreaming = false)
            _messages.value = currentList
        }
    }
    
    fun clearChat() {
        _messages.value = emptyList()
    }
}

data class ChatMessage(
    val role: String,
    val content: String,
    val isStreaming: Boolean = false
)
