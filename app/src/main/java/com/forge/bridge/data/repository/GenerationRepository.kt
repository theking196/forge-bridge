package com.forge.bridge.data.repository

import com.forge.bridge.data.local.KeystoreVault
import com.forge.bridge.data.local.dao.ProviderDao
import com.forge.bridge.data.local.dao.RateLimitDao
import com.forge.bridge.data.model.GenerationChunk
import com.forge.bridge.data.model.GenerationRequest
import com.forge.bridge.data.remote.providers.BaseProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GenerationRepository @Inject constructor(
    private val providerDao: ProviderDao,
    private val rateLimitDao: RateLimitDao,
    private val keystoreVault: KeystoreVault,
    private val providers: Map<String, @JvmSuppressWildcards BaseProvider>
) {
    fun generate(request: GenerationRequest): Flow<GenerationChunk> = flow {
        val providerEntity = providerDao.getProviderById(request.provider)
            ?: throw Exception("Provider not found: ${request.provider}")

        val token = keystoreVault.decrypt(providerEntity.encryptedToken)
        val adapter = providers[providerEntity.type]
            ?: throw Exception("No adapter found for type: ${providerEntity.type}")

        adapter.generate(request, token)
            .onStart {
                rateLimitDao.incrementRequestCount(request.provider)
            }
            .collect {
                emit(it)
            }
    }
}
