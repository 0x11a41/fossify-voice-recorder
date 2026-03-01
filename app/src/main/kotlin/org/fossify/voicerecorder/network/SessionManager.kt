package org.fossify.voicerecorder.network

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.helpers.*
import org.fossify.voicerecorder.services.RecorderService
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.text.lowercase
import kotlin.text.startsWith

interface RecorderController {
    fun start()
    fun stop()
    fun pause()
    fun resume()
    fun cancel()
}

class SessionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val recorder: RecorderController
) {
    private val restClient = RestClient()
    private val discovery = ServerDiscovery(context)
    private val ws = WebSocketManager(scope)
    private var sessionMetadata: SessionMetadata? = null
    var serverBaseUrl: String? = null

    @Volatile private var internalState: SessionStates = SessionStates.STOPPED
    @Volatile private var internalDuration: Int = 0

    var onSessionReady: ((SessionMetadata) -> Unit)? = null
    var onStateChanged: ((SessionStates, Int) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    init {
        registerBus()
    }

    private fun registerBus() {
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
    }

    // --------------------------------------------------
    // DISCOVERY
    // --------------------------------------------------
    suspend fun discoverServers(): List<DiscoveredServer> =
        withContext(Dispatchers.IO) {
            try {
                discovery.discoverServers()
            } catch (e: Exception) {
                Log.e(TAG, "Discovery failed", e)
                onError?.invoke(e.message ?: "Discovery error")
                emptyList()
            }
        }

    // --------------------------------------------------
    // INITIALIZATION
    // --------------------------------------------------

    suspend fun initialize(): Boolean =
        withContext(Dispatchers.IO) {
            val baseUrl = serverBaseUrl ?: run {
                onError?.invoke("Server not selected")
                return@withContext false
            }

            try {
                registerBus()
                
                val result = restClient.stageSession(
                    baseUrl = baseUrl,
                    name = getUserName(),
                    ip = getLocalIp(),
                    device = getDeviceName(),
                    battery = getBatteryLevel()
                )
                val meta = result.getOrElse {
                    onError?.invoke(it.message ?: "Stage failed")
                    return@withContext false
                }

                sessionMetadata = meta
                attachWebSocketCallbacks()
                ws.connect(baseUrl, meta.id, meta.name)
                
                // Request current state immediately after connection
                requestRecorderInfo()
                
                true
            } catch (e: Exception) {
                Log.e(TAG, "Init error", e)
                onError?.invoke(e.message ?: "Init failed")
                false
            }
        }

    // --------------------------------------------------
    // CALLBACK WIRING
    // --------------------------------------------------

    private fun attachWebSocketCallbacks() {
        ws.onSessionActivated = {
            sessionMetadata = it
            onSessionReady?.invoke(it)
        }

        ws.onSessionUpdate = {
            sessionMetadata = it
        }

        ws.onError = {
            onError?.invoke(it)
        }

        // ---------- dashboard commands → recorder ----------

        ws.onStart = {
            recorder.start()
        }

        ws.onStop = {
            recorder.stop()
        }

        ws.onPause = {
            recorder.pause()
        }

        ws.onResume = {
            recorder.resume()
        }

        ws.onCancel = {
            recorder.cancel()
        }

        ws.onGetState = {
            requestRecorderInfo() // Refresh info before sending
            sendState()
        }
    }

    // --------------------------------------------------
    // STATE TRACKING (EventBus)
    // --------------------------------------------------

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRecordingStatusChanged(event: Events.RecordingStatus) {
        val newState = when (event.status) {
            RECORDING_RUNNING -> SessionStates.RUNNING
            RECORDING_PAUSED -> SessionStates.PAUSED
            else -> SessionStates.STOPPED
        }

        if (internalState != newState) {
            val oldState = internalState
            internalState = newState
            
            // Auto-report to server
            if (oldState == SessionStates.PAUSED && newState == SessionStates.RUNNING) {
                reportResumed()
            } else {
                reportStateChange(newState)
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRecordingDurationChanged(event: Events.RecordingDuration) {
        internalDuration = event.duration
        reportDuration(event.duration)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRecordingCompleted(event: Events.RecordingCompleted) {
        internalState = SessionStates.STOPPED
        internalDuration = 0
        reportStateChange(SessionStates.STOPPED)
    }

    // --------------------------------------------------
    // STATE REPORTING
    // --------------------------------------------------

    fun reportStateChange(state: SessionStates) {
        val event = when (state) {
            SessionStates.RUNNING -> WSEvents.STARTED
            SessionStates.PAUSED -> WSEvents.PAUSED
            else -> WSEvents.STOPPED
        }
        
        scope.launch {
            sessionMetadata?.id?.let { id ->
                ws.sendEvent(event, id)
                ws.sendStateReport(state, internalDuration)
            }
            onStateChanged?.invoke(state, internalDuration)
        }
    }

    fun reportResumed() {
        scope.launch {
            sessionMetadata?.id?.let { id ->
                ws.sendEvent(WSEvents.RESUMED, id)
                ws.sendStateReport(SessionStates.RUNNING, internalDuration)
            }
            onStateChanged?.invoke(SessionStates.RUNNING, internalDuration)
        }
    }

    fun reportDuration(duration: Int) {
        scope.launch {
            sessionMetadata?.id?.let {
                ws.sendStateReport(internalState, duration)
            }
        }
    }

    fun sendState() {
        scope.launch {
            sessionMetadata?.id?.let {
                ws.sendStateReport(internalState, internalDuration)
            }
            onStateChanged?.invoke(internalState, internalDuration)
        }
    }

    private fun requestRecorderInfo() {
        val intent = Intent(context, RecorderService::class.java).apply {
            action = GET_RECORDER_INFO
        }
        try {
            context.startService(intent)
        } catch (_: Exception) {}
    }

    // --------------------------------------------------
    // LIFECYCLE
    // --------------------------------------------------

    fun disconnect() {
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
        ws.disconnect()
        sessionMetadata = null
    }

    // --------------------------------------------------
    // UTILITIES
    // --------------------------------------------------
    private fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        return if (model.lowercase().startsWith(manufacturer.lowercase())) {
            model.replaceFirstChar { it.uppercase() }
        } else {
            "$manufacturer $model".replaceFirstChar { it.uppercase() }
        }
    }
    private fun getLocalIp(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.interfaceAddresses) {
                    val ip = addr.address
                    if (ip is Inet4Address)
                        return ip.hostAddress ?: continue
                }
            }
            "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }

    private fun getBatteryLevel(): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Exception) {
            50
        }
    }

    private fun getUserName(): String {
        val prefs = context.getSharedPreferences("m_recorder_prefs", Context.MODE_PRIVATE)
        val defaultName = "User ${Build.MODEL.takeLast(4)}"
        return prefs.getString("display_name", null) ?: defaultName
    }

    companion object {
        private const val TAG = "SessionManager"
    }
}
