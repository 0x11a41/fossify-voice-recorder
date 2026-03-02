package org.fossify.voicerecorder.fragments

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showKeyboard
import org.fossify.voicerecorder.R
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
            
            // Set initial name and IP
            val sm = sessionManager!!
            updateHeader(sm.getUserName(), sm.getLocalIp())
        }

        setupColors()

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

        binding.editIcon.setOnClickListener {
            showRenameDialog()
        }

        binding.qrIcon.setOnClickListener {
            startQrScanner()
        }

        refreshServers()
    }

    private fun setupColors() {
        val textColor = context.getProperTextColor()
        binding.userName.setTextColor(textColor)
        binding.ipAddress.setTextColor(textColor)
        binding.noServersFound.setTextColor(textColor)
        
        val primaryColor = context.getProperPrimaryColor()
        binding.refreshIcon.applyColorFilter(primaryColor)
        binding.qrIcon.applyColorFilter(primaryColor)
        binding.editIcon.applyColorFilter(primaryColor)
    }

    private fun updateHeader(name: String, ip: String) {
        binding.userName.text = name
        binding.ipAddress.text = ip
    }

    private fun showRenameDialog() {
        val activity = context as? AppCompatActivity ?: return
        val sm = sessionManager ?: return
        val currentName = sm.getUserName()
        
        val editText = AppCompatEditText(context).apply {
            setText(currentName)
            setSelection(currentName.length)
        }

        val builder = AlertDialog.Builder(context)
            .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    sm.setUserName(newName)
                    updateHeader(newName, sm.getLocalIp())
                }
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)

        activity.setupDialogStuff(editText, builder, R.string.rename) {
            activity.showKeyboard(editText)
        }
    }

    private fun toggleConnection(server: Server) {
        val sm = sessionManager ?: return
        if (server.isConnected) {
            sm.disconnect()
            server.isConnected = false
            serverAdapter.notifyDataSetChanged()
        } else {
            // Disconnect from any other server first
            servers.forEach { if (it.isConnected) {
                it.isConnected = false
            } }
            sm.disconnect()

            sm.serverBaseUrl = "http://${server.ip}:${server.port}"
            activityScope?.launch {
                if (sm.initialize()) {
                    // Force update UI: only one server should be connected
                    servers.forEach { it.isConnected = (it.ip == server.ip && it.port == server.port) }
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
                    updateHeader(meta.name, meta.ip)
                }
            }
        }
    }

    private fun refreshServers() {
        val sm = sessionManager ?: return
        
        // Disable button and start rotation animation
        binding.refreshIcon.isEnabled = false
        val animator = ObjectAnimator.ofFloat(binding.refreshIcon, View.ROTATION, 0f, 360f).apply {
            duration = 1000
            repeatCount = ObjectAnimator.INFINITE
            start()
        }

        activityScope?.launch {
            try {
                val discovered = sm.discoverServers()
                // Keep connection status if the server is still discovered
                val connectedIp = servers.find { it.isConnected }?.ip
                
                servers.clear()
                servers.addAll(discovered.map { 
                    Server(it.name, it.ip, it.port, it.ip == connectedIp) 
                })
                updateServersUI()
            } finally {
                // Stop animation and re-enable button
                animator.cancel()
                binding.refreshIcon.rotation = 0f
                binding.refreshIcon.isEnabled = true
            }
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

    private fun startQrScanner() {
        val activity = context as? AppCompatActivity ?: return
        IntentIntegrator(activity)
            .setOrientationLocked(false)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt(context.getString(R.string.scan_qr_code))
            .setBeepEnabled(false)
            .initiateScan()
    }

    fun onQrCodeScanned(content: String) {
        val qrJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
        
        // Handle Python-style single quoted JSON if necessary
        val normalizedContent = if (content.trim().startsWith("{") && content.contains("'")) {
            content.replace("'", "\"")
        } else {
            content
        }

        try {
            val qrData = qrJson.decodeFromString<QRData>(normalizedContent)
            if (qrData.type == "vocal_link_server") {
                val server = Server(qrData.name, qrData.ip, qrData.port, false)
                // Add to list if not present, then connect
                val existing = servers.find { it.ip == server.ip && it.port == server.port }
                if (existing == null) {
                    servers.add(server)
                }
                updateServersUI()
                toggleConnection(existing ?: server)
            } else {
                Toast.makeText(context, "Invalid QR Code type", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to parse QR Code: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        setupColors()
    }

    override fun onDestroy() {
        sessionManager?.disconnect()
    }

    fun finishActMode() {}

    fun onSearchTextChanged(text: String) {}
}
