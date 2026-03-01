package org.fossify.voicerecorder.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class DiscoveredServer(
    val name: String,
    val ip: String,
    val port: Int,
    val baseUrl: String
)

class ServerDiscovery(context: Context) {
    private val nsdManager: NsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val discoveredServers = mutableMapOf<String, DiscoveredServer>()

    suspend fun discoverServers(timeoutMs: Long = 3000): List<DiscoveredServer> {
        return suspendCancellableCoroutine { continuation ->
            discoveredServers.clear()

            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "Discovery start failed: $errorCode")
                    if (continuation.isActive) {
                        continuation.resume(emptyList())
                    }
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "Discovery stop failed: $errorCode")
                }

                override fun onDiscoveryStarted(serviceType: String) {
                    Log.d(TAG, "Discovery started")
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.d(TAG, "Discovery stopped")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                    resolveService(serviceInfo)
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                    discoveredServers.remove(serviceInfo.serviceName)
                }
            }

            try {
                nsdManager.discoverServices("_vocalink._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting discovery", e)
                if (continuation.isActive) {
                    continuation.resume(emptyList())
                }
            }

            // Stop discovery after timeout
            val handler = Handler(Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                try {
                    nsdManager.stopServiceDiscovery(listener)
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping discovery", e)
                }
                if (continuation.isActive) {
                    continuation.resume(discoveredServers.values.toList())
                }
            }
            handler.postDelayed(timeoutRunnable, timeoutMs)

            continuation.invokeOnCancellation {
                handler.removeCallbacks(timeoutRunnable)
                try {
                    nsdManager.stopServiceDiscovery(listener)
                } catch (e: Exception) {
                    Log.e(TAG, "Error cancelling discovery", e)
                }
            }
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed: $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service resolved: ${serviceInfo.serviceName}")
                val host = serviceInfo.host?.hostAddress ?: return
                val port = serviceInfo.port
                val name = serviceInfo.serviceName.removePrefix("_vocalink._tcp.local.").removeSuffix(".")

                val server = DiscoveredServer(
                    name = name,
                    ip = host,
                    port = port,
                    baseUrl = "http://$host:$port"
                )
                discoveredServers[serviceInfo.serviceName] = server
            }
        }

        try {
            nsdManager.resolveService(serviceInfo, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving service", e)
        }
    }

    companion object {
        private const val TAG = "ServerDiscovery"
    }
}
