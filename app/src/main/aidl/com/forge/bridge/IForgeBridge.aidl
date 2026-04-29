package com.forge.bridge;

interface IForgeBridge {
    /**
     * Get a list of available providers.
     * Returns a JSON string of providers for simplicity in this version.
     */
    String getProvidersJson();

    /**
     * Get the current status of the bridge server.
     */
    String getServerStatus();

    /**
     * Trigger a check for updates.
     */
    void checkUpdates();

    /**
     * Generate content using a specific provider (JSON request).
     */
    String generate(String requestJson);

    /**
     * Connect/Disconnect a provider session (for proxy tiers).
     */
    void connectProvider(String providerId);
    void disconnectProvider(String providerId);
}
