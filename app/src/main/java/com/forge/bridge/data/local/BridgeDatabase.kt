package com.forge.bridge.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.forge.bridge.data.local.dao.AuditLogDao
import com.forge.bridge.data.local.dao.ProviderDao
import com.forge.bridge.data.local.dao.RateLimitDao
import com.forge.bridge.data.local.entities.AuditLogEntity
import com.forge.bridge.data.local.entities.ProviderEntity
import com.forge.bridge.data.local.entities.RateLimitEntity

@Database(entities = [ProviderEntity::class, AuditLogEntity::class, RateLimitEntity::class], version = 1)
abstract class BridgeDatabase : RoomDatabase() {
    abstract fun providerDao(): ProviderDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun rateLimitDao(): RateLimitDao
}
