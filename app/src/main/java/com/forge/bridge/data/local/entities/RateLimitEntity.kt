package com.forge.bridge.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rate_limits")
data class RateLimitEntity(
    @PrimaryKey val providerId: String,
    val requestCount: Int = 0,
    val lastResetTime: Long = System.currentTimeMillis()
)
