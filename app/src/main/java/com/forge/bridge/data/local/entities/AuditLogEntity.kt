package com.forge.bridge.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audit_logs")
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val provider: String,
    val model: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String, // success, error
    val messageCount: Int
)
