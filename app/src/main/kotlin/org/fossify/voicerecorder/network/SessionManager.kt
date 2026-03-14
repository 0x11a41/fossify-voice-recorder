package org.fossify.voicerecorder.network

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import org.fossify.voicerecorder.network.JsonConfig.WS_JSON
import kotlinx.serialization.json.encodeToJsonElement
import org.fossify.commons.extensions.getFilenameFromPath
import java.io.File
import android.provider.OpenableColumns
import android.media.MediaMetadataRetriever

interface RecorderController {
    fun start(triggerTime: Long? = null, theta: Float = 0f)
    fun stop(triggerTime: Long? = null, theta: Float = 0f)
    fun pause(triggerTime: Long? = null, theta: Float = 0f)
    fun resume(triggerTime: Long? = null, theta: Float = 0f)
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
    var sessionMetadata: SessionMetadata? = null
        private set
    var serverBaseUrl: String? = null

    @Volatile private var internalState: SessionStates = SessionStates.STOPPED
    @Volatile private var internalDuration: Int = 0
    private var lastRecordedDuration: Int = 0

    var onSessionReady: ((SessionMetadata) -> Unit)? = null
    var onStateChanged: ((SessionStates, Int) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    // Track pending uploads
    private val pendingUploads = mutableMapOf<String, Uri>()

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

        ws.onStart = { triggerTime ->
            recorder.start(triggerTime, sessionMetadata?.theta ?: 0f)
        }

        ws.onStop = { triggerTime ->
            recorder.stop(triggerTime, sessionMetadata?.theta ?: 0f)
        }

        ws.onPause = { triggerTime ->
            recorder.pause(triggerTime, sessionMetadata?.theta ?: 0f)
        }

        ws.onResume = { triggerTime ->
            recorder.resume(triggerTime, sessionMetadata?.theta ?: 0f)
        }

        ws.onCancel = {
            recorder.cancel()
        }

        ws.onGetState = {
            requestRecorderInfo() // Refresh info before sending
            sendState()
        }

        // ---------- Recording Upload flow ----------

        ws.onRecStaged = { meta ->
            val uri = pendingUploads.remove(meta.recName)
            val baseUrl = serverBaseUrl
            if (uri != null && baseUrl != null) {
                scope.launch {
                    val result = restClient.uploadRecording(baseUrl, meta.rid, context, uri)
                    result.onFailure {
                        Log.e(TAG, "Upload failed for ${meta.rid}", it)
                    }
                }
            }
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
            
            // Auto-report events to server when state changes locally
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
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRecordingCompleted(event: Events.RecordingCompleted) {
        lastRecordedDuration = internalDuration
        internalState = SessionStates.STOPPED
        internalDuration = 0
        reportStateChange(SessionStates.STOPPED)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRecordingSaved(event: Events.RecordingSaved) {
        val uri = event.uri ?: return
        val sessionId = sessionMetadata?.id ?: return
        
        scope.launch(Dispatchers.IO) {
            try {
                // 1. Actual File Name
                val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst()) cursor.getString(nameIndex) else null
                } ?: uri.lastPathSegment ?: "recording"

                // 2. Actual Size
                val fileSize = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { 
                    it.length 
                } ?: 0L
                
                // 3. Actual Duration from File
                val actualDuration = try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, uri)
                    val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    retriever.release()
                    time?.toLong()?.div(1000)?.toInt() ?: lastRecordedDuration
                } catch (e: Exception) {
                    lastRecordedDuration
                }

                // Track this URI by filename to match it when REC_STAGED arrives
                pendingUploads[fileName] = uri
                
                val info = RecStageInfo(
                    sessionId = sessionId,
                    recName = fileName,
                    duration = actualDuration,
                    sizeBytes = fileSize
                )
                
                ws.sendRecStage(info)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stage recording", e)
            }
        }
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
            }
            onStateChanged?.invoke(state, internalDuration)
        }
    }

    fun reportResumed() {
        scope.launch {
            sessionMetadata?.id?.let { id ->
                ws.sendEvent(WSEvents.RESUMED, id)
            }
            onStateChanged?.invoke(SessionStates.RUNNING, internalDuration)
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
        pendingUploads.clear()
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
    
    internal fun getLocalIp(): String {
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

    internal fun getUserName(): String {
        val prefs = context.getSharedPreferences("m_recorder_prefs", Context.MODE_PRIVATE)
        val defaultName = "User ${Build.MODEL.takeLast(4)}"
        return prefs.getString("display_name", null) ?: defaultName
    }

    internal fun setUserName(name: String) {
        val prefs = context.getSharedPreferences("m_recorder_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("display_name", name).apply()
        
        val currentMeta = sessionMetadata ?: return
        val newMeta = currentMeta.copy(name = name)
        sessionMetadata = newMeta
        
        scope.launch {
            ws.send(
                WSKind.EVENT,
                WSEvents.SESSION_UPDATE.name.lowercase(),
                WS_JSON.encodeToJsonElement(newMeta)
            )
        }
    }

    companion object {
        private const val TAG = "SessionManager"
    }
}
