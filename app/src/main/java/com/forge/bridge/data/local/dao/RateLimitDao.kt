package com.forge.bridge.data.local.dao

import androidx.room.*
import com.forge.bridge.data.local.entities.RateLimitEntity

@Dao
interface RateLimitDao {
    @Query("SELECT * FROM rate_limits WHERE providerId = :id")
    suspend fun getRateLimit(id: String): RateLimitEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRateLimit(rateLimit: RateLimitEntity)

    @Query("UPDATE rate_limits SET requestCount = requestCount + 1 WHERE providerId = :id")
    suspend fun incrementRequestCount(id: String)
}
