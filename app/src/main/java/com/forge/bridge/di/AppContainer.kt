package com.forge.bridge.di

import android.content.Context
import com.forge.bridge.data.local.Database
import com.forge.bridge.data.local.VaultManager
import com.forge.bridge.data.remote.adapters.AdapterRegistry
import com.forge.bridge.data.remote.browser.BrowserProviderManager
import com.forge.bridge.data.remote.server.BridgeServer
import com.forge.bridge.service.MdnsAdvertiser
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency injection container.
 * Instantiated once in ForgeBridgeApp. All fields are lazy.
 *
 * Important: [browserProviderManager] creates WebViews and MUST first be accessed
 * on the main thread (BridgeService.onStartCommand satisfies this).
 */
class AppContainer(context: Context) {

    val database: Database by lazy { Database(context) }

    val vaultManager: VaultManager by lazy { VaultManager(context) }

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Manages headless WebViews for browser-tier providers.
     * WebView creation is dispatched to the main thread internally,
     * but the lazy accessor itself can be called from any thread.
     */
    val browserProviderManager: BrowserProviderManager by lazy {
        BrowserProviderManager(context, vaultManager)
    }

    val adapterRegistry: AdapterRegistry by lazy {
        AdapterRegistry(httpClient, browserProviderManager, vaultManager)
    }

    val server: BridgeServer by lazy {
        BridgeServer(database, vaultManager, httpClient, adapterRegistry)
    }

    /**
     * Advertises `_forge-bridge._tcp` over mDNS so Forge OS and local tooling
     * can discover the bridge without a hardcoded port.
     * Lifecycle is tied to [BridgeService].
     */
    val mdnsAdvertiser: MdnsAdvertiser by lazy {
        MdnsAdvertiser(context)
    }
}
