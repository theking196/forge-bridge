package com.forge.bridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.bridge.data.local.KeystoreVault
import com.forge.bridge.data.local.dao.AuditLogDao
import com.forge.bridge.data.local.dao.ProviderDao
import com.forge.bridge.data.local.entities.AuditLogEntity
import com.forge.bridge.data.local.entities.ProviderEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val providerDao: ProviderDao,
    private val auditLogDao: AuditLogDao,
    private val keystoreVault: KeystoreVault
) : ViewModel() {

    val providers: StateFlow<List<ProviderEntity>> = providerDao.getAllProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _recentLogs = MutableStateFlow<List<AuditLogEntity>>(emptyList())
    val recentLogs = _recentLogs.asStateFlow()

    init {
        refreshLogs()
    }

    fun refreshLogs() {
        viewModelScope.launch {
            _recentLogs.value = auditLogDao.getRecentLogs()
        }
    }

    fun addProvider(name: String, type: String, token: String) {
        viewModelScope.launch {
            val encryptedToken = keystoreVault.encrypt(token)
            val provider = ProviderEntity(
                id = UUID.randomUUID().toString(),
                name = name,
                type = type,
                encryptedToken = encryptedToken
            )
            providerDao.upsertProvider(provider)
        }
    }

    fun deleteProvider(id: String) {
        viewModelScope.launch {
            providerDao.deleteProvider(id)
        }
    }
}
