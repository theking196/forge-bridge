package com.forge.bridge.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

private const val TAG = "MdnsAdvertiser"
private const val SERVICE_NAME = "ForgeBridge"
private const val SERVICE_TYPE = "_forge-bridge._tcp."
private const val BRIDGE_PORT = 8745

/**
 * Advertises Forge Bridge over mDNS (DNS-SD) using Android's NsdManager.
 * Forge OS and other local tooling discover the bridge by resolving
 * `_forge-bridge._tcp.local.` — no hardcoded port required on the client side.
 *
 * Lifecycle: call [start] when [BridgeService] starts, [stop] when it stops.
 */
class MdnsAdvertiser(context: Context) {

    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    @Volatile
    private var listener: NsdManager.RegistrationListener? = null

    @Volatile
    private var registered = false

    fun start() {
        if (registered) {
            Log.d(TAG, "Already advertising — skip")
            return
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            port = BRIDGE_PORT
        }

        val l = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed: error $errorCode")
                registered = false
                listener = null
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS unregistration failed: error $errorCode")
            }
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registered = true
                Log.i(TAG, "mDNS registered: ${info.serviceName} · $SERVICE_TYPE · port $BRIDGE_PORT")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                registered = false
                Log.i(TAG, "mDNS unregistered: ${info.serviceName}")
            }
        }

        listener = l
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mDNS advertisement", e)
            listener = null
            registered = false
        }
    }

    fun stop() {
        val l = listener ?: return
        try {
            nsdManager.unregisterService(l)
        } catch (e: Exception) {
            Log.w(TAG, "Error during mDNS unregistration", e)
        } finally {
            listener = null
            registered = false
        }
    }

    val isAdvertising: Boolean get() = registered
}
