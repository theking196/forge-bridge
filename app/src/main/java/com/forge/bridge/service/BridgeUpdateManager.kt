package com.forge.bridge.service

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class UpdateInfo(
    val latestVersion: String,
    val downloadUrl: String,
    val releaseNotes: String
)

@Singleton
class BridgeUpdateManager @Inject constructor(
    private val client: HttpClient
) {
    private val updateUrl = "https://raw.githubusercontent.com/forge/bridge/main/update.json"

    suspend fun checkForUpdates(currentVersion: String): UpdateInfo? {
        return try {
            val update: UpdateInfo = client.get(updateUrl).body()
            if (update.latestVersion != currentVersion) update else null
        } catch (e: Exception) {
            null
        }
    }
}
