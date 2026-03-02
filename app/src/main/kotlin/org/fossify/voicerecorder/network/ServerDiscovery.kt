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
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    suspend fun discoverServers(timeoutMs: Long = 3000): List<DiscoveredServer> =
        suspendCancellableCoroutine { cont ->
            val results = mutableMapOf<String, DiscoveredServer>()
            val lock = Any()
            val handler = Handler(Looper.getMainLooper())

            val discoveryListener = object : NsdManager.DiscoveryListener {
                
                private fun safeStop() {
                    try {
                        nsdManager.stopServiceDiscovery(this)
                    } catch (_: Exception) {}
                }

                override fun onStartDiscoveryFailed(t: String, code: Int) {
                    Log.e(TAG, "Start failed: $code")
                    if (cont.isActive) {
                        cont.resume(emptyList())
                    }
                }

                override fun onStopDiscoveryFailed(t: String, code: Int) {
                    Log.e(TAG, "Stop failed: $code")
                }

                override fun onDiscoveryStarted(t: String) {
                    Log.d(TAG, "Discovery started")
                }

                override fun onDiscoveryStopped(t: String) {
                    Log.d(TAG, "Discovery stopped")
                }

                override fun onServiceFound(info: NsdServiceInfo) {
                    Log.d(TAG, "Service found: ${info.serviceName}")
                    
                    // Create a unique listener for each resolution to avoid "listener already in use" crashes
                    val resolveListener = object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                            Log.e(TAG, "Resolve failed for ${info.serviceName}: $code")
                        }

                        override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                            Log.d(TAG, "Service resolved: ${resolvedInfo.serviceName}")
                            val host = resolvedInfo.host?.hostAddress ?: return
                            val port = resolvedInfo.port
                            val name = resolvedInfo.serviceName
                                .removePrefix("_vocalink._tcp.local.")
                                .removeSuffix(".")
                            
                            synchronized(lock) {
                                results[resolvedInfo.serviceName] = DiscoveredServer(
                                    name = name,
                                    ip = host,
                                    port = port,
                                    baseUrl = "http://$host:$port"
                                )
                            }
                        }
                    }
                    
                    try {
                        nsdManager.resolveService(info, resolveListener)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error calling resolveService", e)
                    }
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    synchronized(lock) {
                        results.remove(info.serviceName)
                    }
                }
            }

            val timeoutRunnable = Runnable {
                try {
                    nsdManager.stopServiceDiscovery(discoveryListener)
                } catch (_: Exception) {}

                val list = synchronized(lock) {
                    results.values.toList()
                }

                if (cont.isActive) {
                    cont.resume(list)
                }
            }

            try {
                nsdManager.discoverServices(
                    "_vocalink._tcp.",
                    NsdManager.PROTOCOL_DNS_SD,
                    discoveryListener
                )
                handler.postDelayed(timeoutRunnable, timeoutMs)
            } catch (e: Exception) {
                Log.e(TAG, "Discover error", e)
                if (cont.isActive) cont.resume(emptyList())
            }

            cont.invokeOnCancellation {
                handler.removeCallbacks(timeoutRunnable)
                try {
                    nsdManager.stopServiceDiscovery(discoveryListener)
                } catch (_: Exception) {}
            }
        }

    companion object {
        private const val TAG = "ServerDiscovery"
    }
}
