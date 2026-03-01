package org.fossify.voicerecorder.fragments

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import org.fossify.voicerecorder.adapters.ServerAdapter
import org.fossify.voicerecorder.databinding.FragmentNetworkBinding
import org.fossify.voicerecorder.models.Server
import org.fossify.voicerecorder.network.*
import org.fossify.voicerecorder.helpers.*
import org.fossify.voicerecorder.services.RecorderService

class NetworkFragment(context: Context, attributeSet: AttributeSet) : MyViewPagerFragment(context, attributeSet), RecorderController {
    private lateinit var binding: FragmentNetworkBinding
    private lateinit var serverAdapter: ServerAdapter
    private val servers = mutableListOf<Server>()
    private var sessionManager: SessionManager? = null

    private val activityScope get() = (context as? AppCompatActivity)?.lifecycleScope

    override fun start() {
        val intent = Intent(context, RecorderService::class.java)
        context.startService(intent)
    }

    override fun stop() {
        val intent = Intent(context, RecorderService::class.java)
        context.stopService(intent)
    }

    override fun pause() {
        val intent = Intent(context, RecorderService::class.java).apply {
            action = TOGGLE_PAUSE
        }
        context.startService(intent)
    }

    override fun resume() {
        val intent = Intent(context, RecorderService::class.java).apply {
            action = TOGGLE_PAUSE
        }
        context.startService(intent)
    }

    override fun cancel() {
        val intent = Intent(context, RecorderService::class.java).apply {
            action = CANCEL_RECORDING
        }
        context.startService(intent)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentNetworkBinding.bind(this)

        val scope = activityScope
        if (scope != null) {
            sessionManager = SessionManager(context, scope, this)
            setupSessionManager()
        }

        serverAdapter = ServerAdapter(servers) { server ->
            toggleConnection(server)
        }
        
        binding.serversList.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = serverAdapter
        }

        binding.refreshIcon.setOnClickListener {
            refreshServers()
        }

        refreshServers()
    }

    private fun toggleConnection(server: Server) {
        val sm = sessionManager ?: return
        if (server.isConnected) {
            sm.disconnect()
            server.isConnected = false
            serverAdapter.notifyDataSetChanged()
        } else {
            // Disconnect from any other server first
            servers.forEach { if (it.isConnected) it.isConnected = false }
            sm.disconnect()

            sm.serverBaseUrl = "http://${server.ip}:${server.port}"
            activityScope?.launch {
                if (sm.initialize()) {
                    server.isConnected = true
                    serverAdapter.notifyDataSetChanged()
                } else {
                    server.isConnected = false
                    serverAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun setupSessionManager() {
        sessionManager?.apply {
            onError = { message ->
                activityScope?.launch {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
            onSessionReady = { meta ->
                activityScope?.launch {
                    Toast.makeText(context, "Connected to ${meta.name}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun refreshServers() {
        activityScope?.launch {
            val discovered = sessionManager?.discoverServers() ?: emptyList()
            // Keep connection status if the server is still discovered
            val connectedIp = servers.find { it.isConnected }?.ip
            
            servers.clear()
            servers.addAll(discovered.map { 
                Server(it.name, it.ip, it.port, it.ip == connectedIp) 
            })
            updateServersUI()
        }
    }

    private fun updateServersUI() {
        if (servers.isEmpty()) {
            binding.noServersFound.visibility = View.VISIBLE
            binding.serversList.visibility = View.GONE
        } else {
            binding.noServersFound.visibility = View.GONE
            binding.serversList.visibility = View.VISIBLE
        }
        serverAdapter.notifyDataSetChanged()
    }

    override fun onResume() {
        // State tracking is now handled by SessionManager via EventBus in the background
    }

    override fun onDestroy() {
        sessionManager?.disconnect()
    }

    fun finishActMode() {}

    fun onSearchTextChanged(text: String) {}
}
