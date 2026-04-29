package com.forge.bridge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.forge.bridge.data.local.entities.AuditLogEntity

@Dao
interface AuditLogDao {
    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC LIMIT 100")
    suspend fun getRecentLogs(): List<AuditLogEntity>

    @Insert
    suspend fun insertLog(log: AuditLogEntity)
}
