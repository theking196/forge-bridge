package com.forge.bridge.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.forge.bridge.ForgeBridgeApp
import com.forge.bridge.R
import com.forge.bridge.ui.MainActivity

private const val TAG = "BridgeService"
const val ACTION_START = "com.forge.bridge.START"
const val ACTION_STOP = "com.forge.bridge.STOP"

/**
 * ForegroundService that hosts the NanoHTTPD server and manages the mDNS advertisement.
 * Keeps the server alive even when MainActivity is not in the foreground.
 *
 * Lifecycle:
 *  - START_STICKY so Android restarts the service if it's killed
 *  - On start: NanoHTTPD on 127.0.0.1:8745 + mDNS `_forge-bridge._tcp` advertisement
 *  - On stop: server stopped, mDNS unregistered, browser WebViews destroyed
 */
class BridgeService : Service() {

    private val container      get() = (application as ForgeBridgeApp).container
    private val server         get() = container.server
    private val db             get() = container.database
    private val browserManager get() = container.browserProviderManager
    private val mdns           get() = container.mdnsAdvertiser

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startEverything()
        }
        return START_STICKY
    }

    private fun startEverything() {
        if (server.isAlive) {
            Log.d(TAG, "Server already running on port 8745")
            return
        }
        try {
            server.start()
            Log.i(TAG, "Forge Bridge server started on 127.0.0.1:8745")

            // Start mDNS advertisement so Forge OS can discover the bridge
            mdns.start()
            Log.i(TAG, "mDNS advertising _forge-bridge._tcp:8745")

            // Touch browserProviderManager on the main thread to ensure WebViews are
            // initialized early. The lazy accessor itself is safe to call here.
            @Suppress("UNUSED_EXPRESSION")
            browserManager

            val connectedCount = db.getProviders().count { it.status == "connected" }
            startForeground(NOTIFICATION_ID, buildNotification(connectedCount))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            stopSelf()
        }
    }

    private fun stopEverything() {
        mdns.stop()
        if (server.isAlive) {
            server.stop()
            Log.i(TAG, "Forge Bridge server stopped")
        }
    }

    fun updateNotification(connectedProviders: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(connectedProviders))
    }

    private fun buildNotification(connectedProviders: Int): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, BridgeService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val providerText = when (connectedProviders) {
            0 -> "No providers connected"
            1 -> "1 provider connected"
            else -> "$connectedProviders providers connected"
        }
        return NotificationCompat.Builder(this, ForgeBridgeApp.CHANNEL_ID)
            .setContentTitle("Forge Bridge — Running")
            .setContentText("localhost:8745 · $providerText · mDNS active")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    override fun onDestroy() {
        stopEverything()
        browserManager.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 1001
    }
}
