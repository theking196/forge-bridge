package com.forge.bridge.data.repository

import com.forge.bridge.data.local.KeystoreVault
import com.forge.bridge.data.local.dao.ProviderDao
import com.forge.bridge.data.local.dao.RateLimitDao
import com.forge.bridge.data.local.entities.ProviderEntity
import com.forge.bridge.data.model.GenerationChunk
import com.forge.bridge.data.model.GenerationRequest
import com.forge.bridge.data.remote.providers.BaseProvider
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.verify

class GenerationRepositoryTest {

    @Mock lateinit var providerDao: ProviderDao
    @Mock lateinit var rateLimitDao: RateLimitDao
    @Mock lateinit var keystoreVault: KeystoreVault
    @Mock lateinit var mockProvider: BaseProvider

    private lateinit var repository: GenerationRepository

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        repository = GenerationRepository(
            providerDao,
            rateLimitDao,
            keystoreVault,
            mapOf("openai" to mockProvider)
        )
    }

    @Test
    fun `generate should increment rate limit and return chunks`() = runBlocking {
        val request = GenerationRequest(provider = "test-id", model = "gpt-4", messages = emptyList())
        val providerEntity = ProviderEntity("test-id", "Test", "openai", "encrypted-token")
        
        `when`(providerDao.getProviderById("test-id")).thenReturn(providerEntity)
        `when`(keystoreVault.decrypt("encrypted-token")).thenReturn("decrypted-token")
        `when`(mockProvider.generate(any(), any())).thenReturn(flowOf(GenerationChunk("content", "Hello")))

        val result = repository.generate(request).toList()

        verify(rateLimitDao).incrementRequestCount("test-id")
        assertEquals(1, result.size)
        assertEquals("Hello", result[0].chunk)
    }
}
