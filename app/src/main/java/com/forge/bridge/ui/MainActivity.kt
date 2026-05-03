package com.forge.bridge.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.forge.bridge.ForgeBridgeApp
import com.forge.bridge.R
import com.forge.bridge.databinding.ActivityMainBinding
import com.forge.bridge.service.ACTION_START
import com.forge.bridge.service.ACTION_STOP
import com.forge.bridge.service.BridgeService
import com.forge.bridge.ui.providers.ProviderListActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val server get() = (application as ForgeBridgeApp).container.server
    private val db get() = (application as ForgeBridgeApp).container.database

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.btnToggle.setOnClickListener { toggleServer() }
        binding.btnManageProviders.setOnClickListener {
            startActivity(Intent(this, ProviderListActivity::class.java))
        }

        // Poll server status every second to keep UI in sync
        lifecycleScope.launch {
            while (isActive) {
                updateUi()
                delay(1_000)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    private fun toggleServer() {
        val intent = Intent(this, BridgeService::class.java)
        if (server.isAlive) {
            intent.action = ACTION_STOP
            startService(intent)
        } else {
            intent.action = ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    private fun updateUi() {
        lifecycleScope.launch(Dispatchers.IO) {
            val isRunning = server.isAlive
            val stats = runCatching { db.getStats() }.getOrNull()
            val providers = runCatching { db.getProviders() }.getOrNull() ?: emptyList()
            val connected = providers.count { it.status == "connected" }

            withContext(Dispatchers.Main) {
                if (isRunning) {
                    binding.statusIndicator.setBackgroundResource(R.drawable.dot_connected)
                    binding.tvStatus.text = "Running on localhost:8745"
                    binding.tvStatus.setTextColor(getColor(R.color.status_connected))
                    binding.btnToggle.text = "Stop Server"
                } else {
                    binding.statusIndicator.setBackgroundResource(R.drawable.dot_disconnected)
                    binding.tvStatus.text = "Server stopped"
                    binding.tvStatus.setTextColor(getColor(R.color.on_surface_secondary))
                    binding.btnToggle.text = "Start Server"
                }

                binding.tvProviders.text = "$connected / ${providers.size} providers connected"
                binding.tvRequests.text = "${stats?.totalRequests ?: 0} total requests"
                binding.tvUptime.text = if (isRunning) "Uptime: active" else "Uptime: —"

                binding.tvProviderList.text = providers.joinToString("\n") { p ->
                    val dot = if (p.status == "connected") "●" else "○"
                    val default = if (p.isDefault) "  ★" else ""
                    "$dot  ${p.name}  [${p.tier}]$default"
                }
            }
        }
    }
}
