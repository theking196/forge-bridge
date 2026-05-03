package com.forge.bridge.ui.providers

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.forge.bridge.ForgeBridgeApp
import com.forge.bridge.data.model.ProviderRow
import com.forge.bridge.databinding.ActivityProviderListBinding
import com.forge.bridge.ui.browser.WebLoginActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProviderListActivity : AppCompatActivity(), ProviderListAdapter.Listener {

    private lateinit var binding: ActivityProviderListBinding
    private lateinit var listAdapter: ProviderListAdapter

    private val db      get() = (application as ForgeBridgeApp).container.database
    private val vault   get() = (application as ForgeBridgeApp).container.vaultManager
    private val adapters get() = (application as ForgeBridgeApp).container.adapterRegistry

    // Currently awaiting login result for this provider ID
    private var pendingLoginProviderId: String? = null

    private val webLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val name = db.getProvider(pendingLoginProviderId ?: "")?.name ?: "Provider"
            Toast.makeText(this, "$name connected", Toast.LENGTH_SHORT).show()
        }
        pendingLoginProviderId = null
        loadProviders()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProviderListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Providers"

        listAdapter = ProviderListAdapter(this)
        binding.recyclerView.apply {
            adapter = listAdapter
            layoutManager = LinearLayoutManager(this@ProviderListActivity)
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
        }

        loadProviders()
    }

    override fun onResume() {
        super.onResume()
        loadProviders()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun loadProviders() {
        lifecycleScope.launch(Dispatchers.IO) {
            val providers = db.getProviders()
            withContext(Dispatchers.Main) { listAdapter.submitList(providers) }
        }
    }

    // ─── ProviderListAdapter.Listener ─────────────────────────────────────────

    override fun onConnectClick(provider: ProviderRow) {
        if (provider.tier == "proxy") {
            // Proxy providers authenticate via a WebView login
            val intent = when (provider.id) {
                "chatgpt-proxy" -> WebLoginActivity.intentFor(
                    this, provider.id,
                    com.forge.bridge.data.remote.adapters.ChatGPTProxyAdapter.LOGIN_URL,
                    com.forge.bridge.data.remote.adapters.ChatGPTProxyAdapter.COOKIE_DOMAIN,
                )
                "claude-proxy" -> WebLoginActivity.intentFor(
                    this, provider.id,
                    com.forge.bridge.data.remote.adapters.ClaudeProxyAdapter.LOGIN_URL,
                    com.forge.bridge.data.remote.adapters.ClaudeProxyAdapter.COOKIE_DOMAIN,
                )
                else -> null
            }
            if (intent != null) {
                pendingLoginProviderId = provider.id
                webLoginLauncher.launch(intent)
            } else {
                Toast.makeText(this, "No login URL configured for ${provider.id}", Toast.LENGTH_SHORT).show()
            }
        } else {
            // API providers use the key-entry dialog
            ConnectDialogFragment
                .newInstance(provider.id, provider.name, provider.id == "ollama-local")
                .show(supportFragmentManager, "connect_${provider.id}")
        }
    }

    override fun onDisconnectClick(provider: ProviderRow) {
        lifecycleScope.launch(Dispatchers.IO) {
            vault.deleteApiKey(provider.id)
            vault.deleteSessionToken(provider.id)
            db.updateProviderStatus(provider.id, "disconnected")
            val updated = db.getProviders()
            withContext(Dispatchers.Main) {
                listAdapter.submitList(updated)
                Toast.makeText(this@ProviderListActivity, "${provider.name} disconnected", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onTestClick(provider: ProviderRow) {
        val adapter = adapters.get(provider.id) ?: run {
            Toast.makeText(this, "No adapter for ${provider.id}", Toast.LENGTH_SHORT).show()
            return
        }
        val apiKey = vault.getApiKey(provider.id) ?: ""
        Toast.makeText(this, "Testing ${provider.name}…", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { adapter.testConnection(apiKey) }.getOrNull()
            withContext(Dispatchers.Main) {
                val msg = when {
                    result == null       -> "Test failed — check Logcat"
                    result.success       -> "✓ Connected · ${result.latencyMs}ms · ${result.model}"
                    else                 -> "✗ ${result.message}"
                }
                Toast.makeText(this@ProviderListActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Called by ConnectDialogFragment (API-key providers) after a successful save. */
    fun onProviderConnected() { loadProviders() }
}
