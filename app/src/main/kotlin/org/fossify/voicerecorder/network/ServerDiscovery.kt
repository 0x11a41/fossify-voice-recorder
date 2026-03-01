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

            val resolveListener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                    Log.e(TAG, "Resolve failed: $code")
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress ?: return
                    val port = info.port
                    val name = info.serviceName
                        .removePrefix("_vocalink._tcp.local.")
                        .removeSuffix(".")
                    synchronized(lock) {
                        results[info.serviceName] =
                            DiscoveredServer(
                                name = name,
                                ip = host,
                                port = port,
                                baseUrl = "http://$host:$port"
                            )
                    }
                }
            }

            val discoveryListener =
                object : NsdManager.DiscoveryListener {
                    override fun onStartDiscoveryFailed(t: String, code: Int) {
                        Log.e(TAG, "Start failed: $code")
                        stop()
                        if (cont.isActive) cont.resume(emptyList())
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
                        nsdManager.resolveService(info, resolveListener)
                    }

                    override fun onServiceLost(info: NsdServiceInfo) {
                        synchronized(lock) {
                            results.remove(info.serviceName)
                        }
                    }
                    fun stop() {
                        try {
                            nsdManager.stopServiceDiscovery(this)
                        } catch (_: Exception) {}
                    }
                }

            try {
                nsdManager.discoverServices(
                    "_vocalink._tcp.",
                    NsdManager.PROTOCOL_DNS_SD,
                    discoveryListener
                )
            } catch (e: Exception) {
                Log.e(TAG, "Discover error", e)
                cont.resume(emptyList())
                return@suspendCancellableCoroutine
            }

            val handler = Handler(Looper.getMainLooper())

            val timeout = Runnable {
                discoveryListener.stop()

                val list = synchronized(lock) {
                    results.values.toList()
                }

                if (cont.isActive)
                    cont.resume(list)
            }

            handler.postDelayed(timeout, timeoutMs)

            cont.invokeOnCancellation {
                handler.removeCallbacks(timeout)
                discoveryListener.stop()
            }
        }

    companion object {
        private const val TAG = "ServerDiscovery"
    }
}
