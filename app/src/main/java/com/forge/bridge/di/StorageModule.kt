package com.forge.bridge.di

import android.content.Context
import androidx.room.Room
import com.forge.bridge.data.local.BridgeDatabase
import com.forge.bridge.data.local.dao.ProviderDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BridgeDatabase {
        return Room.databaseBuilder(
            context,
            BridgeDatabase::class.java,
            "forge_bridge_db"
        ).build()
    }

    @Provides
    fun provideProviderDao(database: BridgeDatabase): ProviderDao {
        return database.providerDao()
    }

    @Provides
    fun provideAuditLogDao(database: BridgeDatabase): AuditLogDao {
        return database.auditLogDao()
    }

    @Provides
    fun provideRateLimitDao(database: BridgeDatabase): RateLimitDao {
        return database.rateLimitDao()
    }
}
