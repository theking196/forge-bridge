package com.forge.bridge.ui.permissions

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.forge.bridge.ForgeBridgeApp
import com.forge.bridge.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Shown when Forge OS (or another caller holding the SEND_AI_REQUEST permission)
 * attempts to send an AI request but the user has not yet granted trust.
 *
 * Presents a modal dialog explaining what Forge OS wants and lets the user
 * Allow or Deny. The decision is persisted in [VaultManager] so subsequent
 * requests skip this activity entirely.
 *
 * The activity uses a dialog window style (declared in the manifest) so there
 * is no visible background behind the alert.
 */
class PermissionActivity : AppCompatActivity() {

    private val vault get() = (application as ForgeBridgeApp).container.vaultManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requester = intent.getStringExtra(EXTRA_REQUESTER)
            ?.takeIf { it.isNotBlank() && it != "unknown" }
            ?.let { "\n\nRequested by: $it" }
            ?: ""

        MaterialAlertDialogBuilder(this)
            .setTitle("Allow AI Requests?")
            .setIcon(R.drawable.ic_notification)
            .setMessage(
                "An external app wants to send AI requests through Forge Bridge " +
                "and use your connected providers (ChatGPT, Claude, OpenAI, etc.)." +
                requester +
                "\n\nAll requests still go through localhost only — nothing leaves your device."
            )
            .setCancelable(false)
            .setPositiveButton("Allow") { _, _ ->
                vault.storeForgeOsTrust(true)
                setResult(RESULT_OK)
                finish()
            }
            .setNegativeButton("Deny") { _, _ ->
                vault.storeForgeOsTrust(false)
                setResult(RESULT_CANCELED)
                finish()
            }
            .setOnDismissListener { finish() }
            .show()
    }

    companion object {
        const val EXTRA_REQUESTER = "requester"
    }
}
