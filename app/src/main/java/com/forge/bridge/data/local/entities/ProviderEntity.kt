package com.forge.bridge.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String, // openai, anthropic, chatgpt-proxy, claude-proxy
    val encryptedToken: String,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
