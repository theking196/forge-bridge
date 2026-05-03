package com.forge.bridge.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.forge.bridge.ForgeBridgeApp
import com.forge.bridge.ui.permissions.PermissionActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "ForgeIntentReceiver"

/**
 * Receives `com.forge.ACTION_AI_REQUEST` broadcasts from Forge OS.
 *
 * Security layers:
 *  1. Manifest declares `android:permission="com.forge.bridge.permission.SEND_AI_REQUEST"`
 *     so only callers that hold this permission can reach the receiver.
 *  2. VaultManager trust flag (`forge_os_trusted`) — set via [PermissionActivity] on first use.
 *     If not yet granted, PermissionActivity is launched and the request is dropped (caller
 *     should retry after the user allows access).
 *
 * Intent extras:
 *  - [EXTRA_MESSAGE]  (String, required)  — user message text
 *  - [EXTRA_PROVIDER] (String, optional)  — specific provider ID, falls back to default
 *  - [EXTRA_MODEL]    (String, optional)  — model name override
 *  - [EXTRA_REPLY_PI] (PendingIntent, optional) — if supplied, result JSON is sent back
 *
 * The HTTP call to the local server is done on a background thread via [goAsync].
 */
class ForgeIntentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_AI_REQUEST) return

        val app = context.applicationContext as ForgeBridgeApp
        val vault = app.container.vaultManager

        // ── Trust check ───────────────────────────────────────────────────────
        if (!vault.isForgeOsTrusted()) {
            Log.i(TAG, "Forge OS not yet trusted — launching PermissionActivity")
            context.startActivity(
                Intent(context, PermissionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(PermissionActivity.EXTRA_REQUESTER, callingPackageSafe(intent))
                }
            )
            return
        }

        // ── Extract payload ───────────────────────────────────────────────────
        val message = intent.getStringExtra(EXTRA_MESSAGE)
        if (message.isNullOrBlank()) {
            Log.w(TAG, "Received $ACTION_AI_REQUEST with missing $EXTRA_MESSAGE — ignoring")
            return
        }
        val provider = intent.getStringExtra(EXTRA_PROVIDER)
        val model = intent.getStringExtra(EXTRA_MODEL)
        @Suppress("DEPRECATION")
        val replyPi = intent.getParcelableExtra<PendingIntent>(EXTRA_REPLY_PI)

        val pendingResult = goAsync()

        Thread {
            val responseJson = try {
                dispatchToServer(app, message, provider, model)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dispatch AI request to local server", e)
                """{"error":"${e.message?.replace("\"", "'")}"}"""
            }

            replyPi?.let { pi ->
                try {
                    pi.send(context, 0, Intent().apply {
                        putExtra(EXTRA_RESPONSE, responseJson)
                    })
                } catch (e: PendingIntent.CanceledException) {
                    Log.d(TAG, "Reply PendingIntent was cancelled")
                }
            }

            pendingResult.finish()
        }.also { it.name = "ForgeIntentReceiver-worker"; it.isDaemon = true }.start()
    }

    private fun dispatchToServer(
        app: ForgeBridgeApp,
        message: String,
        provider: String?,
        model: String?,
    ): String {
        val bodyMap = buildMap {
            if (provider != null) put("provider", provider)
            if (model != null) put("model", model)
            put("messages", listOf(mapOf("role" to "user", "content" to message)))
            put("stream", false)
        }
        val bodyJson = com.google.gson.GsonBuilder().create().toJson(bodyMap)
        val req = Request.Builder()
            .url("http://127.0.0.1:8745/api/v1/generate")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .header("X-Forge-Internal", "intent-receiver")
            .build()
        return app.container.httpClient.newCall(req).execute().use { resp ->
            resp.body?.string() ?: """{"error":"Empty response from server"}"""
        }
    }

    private fun callingPackageSafe(intent: Intent): String =
        callingPackage ?: intent.getStringExtra(EXTRA_REQUESTER_PACKAGE) ?: "unknown"

    companion object {
        const val ACTION_AI_REQUEST = "com.forge.ACTION_AI_REQUEST"

        const val EXTRA_MESSAGE = "message"
        const val EXTRA_PROVIDER = "provider"
        const val EXTRA_MODEL = "model"
        const val EXTRA_REPLY_PI = "replyPendingIntent"
        const val EXTRA_RESPONSE = "response"
        const val EXTRA_REQUESTER_PACKAGE = "requesterPackage"
    }
}
