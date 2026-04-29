package com.forge.bridge.service

import com.forge.bridge.data.local.KeystoreVault
import com.forge.bridge.data.local.dao.ProviderDao
import com.forge.bridge.data.remote.providers.chatgpt.ChatGptProxyAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenRefreshScheduler @Inject constructor(
    private val providerDao: ProviderDao,
    private val chatGptAdapter: ChatGptProxyAdapter,
    private val keystoreVault: KeystoreVault
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            while (true) {
                refreshAllSessionTokens()
                delay(4 * 60 * 60 * 1000) // Every 4 hours
            }
        }
    }

    private suspend fun refreshAllSessionTokens() {
        val providers = providerDao.getProvidersList()
        providers.filter { it.type == "chatgpt-proxy" }.forEach { provider ->
            try {
                val sessionToken = keystoreVault.decrypt(provider.encryptedToken)
                chatGptAdapter.refreshAccessToken(sessionToken)
            } catch (e: Exception) {
                // Log failure to refresh, but don't crash
            }
        }
    }
}
