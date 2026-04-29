package com.forge.bridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.forge.bridge.IForgeBridge
import com.forge.bridge.data.local.dao.AuditLogDao
import com.forge.bridge.data.local.dao.ProviderDao
import com.forge.bridge.data.remote.api.KtorServer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

@AndroidEntryPoint
class ForgeBridgeService : Service() {

    @Inject
    lateinit var ktorServer: KtorServer

    @Inject
    lateinit var providerDao: ProviderDao

    @Inject
    lateinit var auditLogDao: AuditLogDao

    @Inject
    lateinit var updateManager: BridgeUpdateManager

    @Inject
    lateinit var json: Json

    @Inject
    lateinit var tokenRefreshScheduler: TokenRefreshScheduler

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val binder = object : IForgeBridge.Stub() {
        override fun getProvidersJson(): String {
            // AIDL calls are usually synchronous, but we can wrap in runBlocking for DB access
            return runBlocking {
                val providers = providerDao.getProvidersList()
                json.encodeToString(providers.map { it.copy(encryptedToken = "***") })
            }
        }

        override fun getServerStatus(): String {
            return "Running"
        }

        override fun checkUpdates() {
            serviceScope.launch {
                val update = updateManager.checkForUpdates("1.0")
                if (update != null) {
                    val manager = getSystemService(NotificationManager::class.java)
                    val notification = Notification.Builder(this@ForgeBridgeService, CHANNEL_ID)
                        .setContentTitle("Update Available")
                        .setContentText("Version ${update.latestVersion} is available. Check GitHub.")
                        .setSmallIcon(android.R.drawable.stat_notify_sync)
                        .build()
                    manager.notify(2, notification)
                }
            }
        }

        override fun generate(requestJson: String): String {
            // Simplified synchronous call for AIDL
            // In a production app, we would use a callback AIDL for streaming
            return "Streaming via AIDL is not yet implemented. Use the HTTP endpoint for SSE."
        }

        override fun connectProvider(providerId: String) {
            // Placeholder for triggering auth flows
        }

        override fun disconnectProvider(providerId: String) {
            // Placeholder for clearing sessions
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        serviceScope.launch {
            ktorServer.start()
        }
        tokenRefreshScheduler.start(serviceScope)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        ktorServer.stop()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Forge Bridge Server",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Forge Bridge is Active")
            .setContentText("Local AI API gateway running on port 8745")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "forge_bridge_channel"
        private const val NOTIFICATION_ID = 1
    }
}
