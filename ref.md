### 1. **Primitives/Models** (Kotlin)

```kotlin name=Primitives.kt
package com.example.voicerecorder.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

const val VERSION = "v0.7-alpha"

// ===================== REST API Models =====================

@Serializable
enum class RESTEvents(val value: String) {
    @SerialName("session_stage")
    SESSION_STAGE("session_stage"),
    
    @SerialName("session_staged")
    SESSION_STAGED("session_staged");
    
    override fun toString() = value
}

@Serializable
data class SessionMetadata(
    val id: String,
    val name: String,
    val ip: String,
    val battery: Int? = null,
    val device: String,
    val lastRTT: Float? = null,
    val theta: Float? = null,
    val lastSync: Long? = null
)

@Serializable
data class ServerInfo(
    val name: String,
    val ip: String,
    val version: String = VERSION,
    val activeSessions: Int = 0
)

@Serializable
data class SessionStageRequestMsg(
    val event: String = RESTEvents.SESSION_STAGE.value,
    val body: SessionMetadata
)

@Serializable
data class SessionStageResponseMsg(
    val event: String = RESTEvents.SESSION_STAGED.value,
    val body: SessionMetadata
)

// ===================== WebSocket Models =====================

@Serializable
enum class WSKind(val value: String) {
    @SerialName("action")
    ACTION("action"),
    
    @SerialName("event")
    EVENT("event"),
    
    @SerialName("error")
    ERROR("error"),
    
    @SerialName("sync")
    SYNC("sync");
    
    override fun toString() = value
}

@Serializable
enum class WSErrors(val value: String) {
    @SerialName("invalid_kind")
    INVALID_KIND("invalid_kind"),
    
    @SerialName("invalid_event")
    INVALID_EVENT("invalid_event"),
    
    @SerialName("invalid_action")
    INVALID_ACTION("invalid_action"),
    
    @SerialName("invalid_body")
    INVALID_BODY("invalid_body"),
    
    @SerialName("action_not_allowed")
    ACTION_NOT_ALLOWED("action_not_allowed"),
    
    @SerialName("session_not_found")
    SESSION_NOT_FOUND("session_not_found");
    
    override fun toString() = value
}

@Serializable
enum class WSEvents(val value: String) {
    @SerialName("dashboard_init")
    DASHBOARD_INIT("dashboard_init"),
    
    @SerialName("dashboard_rename")
    DASHBOARD_RENAME("dashboard_rename"),
    
    @SerialName("session_update")
    SESSION_UPDATE("session_update"),
    
    @SerialName("session_activate")
    SESSION_ACTIVATE("session_activate"),
    
    @SerialName("session_activated")
    SESSION_ACTIVATED("session_activated"),
    
    @SerialName("success")
    SUCCESS("success"),
    
    @SerialName("failed")
    FAIL("failed"),
    
    @SerialName("session_state_report")
    SESSION_STATE_REPORT("session_state_report"),
    
    @SerialName("started")
    STARTED("started"),
    
    @SerialName("stopped")
    STOPPED("stopped"),
    
    @SerialName("paused")
    PAUSED("paused"),
    
    @SerialName("resumed")
    RESUMED("resumed"),
    
    @SerialName("dropped")
    DROPPED("dropped");
    
    override fun toString() = value
}

@Serializable
enum class WSActions(val value: String) {
    @SerialName("start")
    START("start"),
    
    @SerialName("stop")
    STOP("stop"),
    
    @SerialName("pause")
    PAUSE("pause"),
    
    @SerialName("resume")
    RESUME("resume"),
    
    @SerialName("cancel")
    CANCEL("cancel"),
    
    @SerialName("drop")
    DROP("drop"),
    
    @SerialName("get_state")
    GET_STATE("get_state");
    
    override fun toString() = value
}

@Serializable
enum class WSClockSync(val value: String) {
    @SerialName("tik")
    TIK("tik"),
    
    @SerialName("tok")
    TOK("tok"),
    
    @SerialName("sync_report")
    SYNC_REPORT("sync_report");
    
    override fun toString() = value
}

@Serializable
enum class SessionStates(val value: String) {
    @SerialName("stopped")
    STOPPED("stopped"),
    
    @SerialName("running")
    RUNNING("running"),
    
    @SerialName("paused")
    PAUSED("paused");
    
    override fun toString() = value
}

// ===================== WebSocket Message Bodies =====================

@Serializable
data class ClockSyncTik(
    val t1: Long
)

@Serializable
data class ClockSyncTok(
    val t1: Long,
    val t2: Long,
    val t3: Long
)

@Serializable
data class ClockSyncReport(
    val theta: Float,
    val rtt: Float
)

@Serializable
data class WSActionTarget(
    val id: String,
    val triggerTime: Long? = null
)

@Serializable
data class WSEventTarget(
    val id: String
)

@Serializable
data class Rename(
    val name: String
)

@Serializable
data class StateReport(
    val id: String,
    val state: String,
    val duration: Int = 0
)

@Serializable
data class WSPayload(
    val kind: String,
    val msgType: String,
    val body: kotlinx.serialization.json.JsonElement? = null
)

@Serializable
data class QRData(
    val type: String = "vocal_link_server",
    val name: String,
    val ip: String
)
```

### 2. **WebSocket Manager** (Kotlin)

```kotlin name=WebSocketManager.kt
package com.example.voicerecorder.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class WebSocketManager(
    private val scope: CoroutineScope
) : WebSocketListener() {
    
    private var webSocket: WebSocket? = null
    private var sessionId: String = ""
    private var sessionName: String = ""
    
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    
    // Clock sync properties
    private var syncInProgress = false
    private var lastSyncReportTime = 0L
    private val SYNC_INTERVAL = 5000L // 5 seconds + random
    
    // Callbacks
    var onSessionActivated: ((SessionMetadata) -> Unit)? = null
    var onSessionUpdate: ((SessionMetadata) -> Unit)? = null
    var onStateChanged: ((String, Int) -> Unit)? = null  // state, duration
    var onSessionDropped: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    
    fun connect(serverUrl: String, sessionId: String, sessionName: String) {
        this.sessionId = sessionId
        this.sessionName = sessionName
        
        val wsUrl = serverUrl.replace("http", "ws") + "/ws/control"
        val request = Request.Builder().url(wsUrl).build()
        
        webSocket = okHttpClient.newWebSocket(request, this)
    }
    
    fun disconnect() {
        syncInProgress = false
        webSocket?.close(1000, "Client disconnecting")
        webSocket = null
    }
    
    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
        Log.d(TAG, "WebSocket connected")
        scope.launch {
            sendSessionActivation()
            onConnected?.invoke()
        }
    }
    
    override fun onMessage(webSocket: WebSocket, text: String) {
        scope.launch {
            try {
                val json = Json.parseToJsonElement(text)
                val payload = json.jsonObject
                
                val kind = payload["kind"]?.jsonPrimitive?.content ?: return@launch
                val msgType = payload["msgType"]?.jsonPrimitive?.content ?: return@launch
                val body = payload["body"]
                
                when (kind) {
                    WSKind.EVENT.value -> handleEvent(msgType, body)
                    WSKind.SYNC.value -> handleSync(msgType, body)
                    WSKind.ERROR.value -> handleError(msgType)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing WebSocket message", e)
            }
        }
    }
    
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
        Log.e(TAG, "WebSocket failure", t)
        onError?.invoke(t.message ?: "WebSocket connection failed")
        onDisconnected?.invoke()
    }
    
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Log.d(TAG, "WebSocket closed: $code - $reason")
        syncInProgress = false
        onDisconnected?.invoke()
    }
    
    // ===================== Private Methods =====================
    
    private suspend fun sendSessionActivation() {
        val payload = WSPayload(
            kind = WSKind.EVENT.value,
            msgType = WSEvents.SESSION_ACTIVATE.value,
            body = Json.encodeToJsonElement(WSEventTarget(sessionId))
        )
        sendMessage(payload)
    }
    
    private fun handleEvent(msgType: String, body: JsonElement?) {
        when (msgType) {
            WSEvents.SESSION_ACTIVATED.value -> {
                val meta = Json.decodeFromJsonElement<SessionMetadata>(body ?: return)
                onSessionActivated?.invoke(meta)
                scope.launch { startSyncScheduler() }
            }
            
            WSEvents.SESSION_UPDATE.value -> {
                val meta = Json.decodeFromJsonElement<SessionMetadata>(body ?: return)
                onSessionUpdate?.invoke(meta)
            }
            
            WSEvents.STARTED.value -> {
                onStateChanged?.invoke(SessionStates.RUNNING.value, 0)
            }
            
            WSEvents.STOPPED.value -> {
                onStateChanged?.invoke(SessionStates.STOPPED.value, 0)
            }
            
            WSEvents.PAUSED.value -> {
                onStateChanged?.invoke(SessionStates.PAUSED.value, 0)
            }
            
            WSEvents.RESUMED.value -> {
                onStateChanged?.invoke(SessionStates.RUNNING.value, 0)
            }
            
            WSEvents.SESSION_STATE_REPORT.value -> {
                val report = Json.decodeFromJsonElement<StateReport>(body ?: return)
                onStateChanged?.invoke(report.state, report.duration)
            }
            
            WSEvents.DROPPED.value -> {
                val target = Json.decodeFromJsonElement<WSEventTarget>(body ?: return)
                onSessionDropped?.invoke(target.id)
            }
        }
    }
    
    private fun handleSync(msgType: String, body: JsonElement?) {
        when (msgType) {
            WSClockSync.TOK.value -> {
                scope.launch {
                    try {
                        val tok = Json.decodeFromJsonElement<ClockSyncTok>(body ?: return@launch)
                        val t4 = currentTimeMs()
                        
                        val rtt = (t4 - tok.t1) - (tok.t3 - tok.t2)
                        val theta = ((tok.t2 - tok.t1) + (tok.t3 - t4)) / 2.0f
                        
                        sendSyncReport(theta, rtt.toFloat())
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling TOK message", e)
                    }
                }
            }
        }
    }
    
    private fun handleError(msgType: String) {
        val message = when (msgType) {
            WSErrors.INVALID_KIND.value -> "Invalid message kind"
            WSErrors.INVALID_EVENT.value -> "Invalid event type"
            WSErrors.INVALID_ACTION.value -> "Invalid action type"
            WSErrors.INVALID_BODY.value -> "Invalid message body"
            WSErrors.ACTION_NOT_ALLOWED.value -> "Action not allowed"
            WSErrors.SESSION_NOT_FOUND.value -> "Session not found"
            else -> "Unknown error: $msgType"
        }
        onError?.invoke(message)
    }
    
    private suspend fun startSyncScheduler() {
        if (syncInProgress) return
        syncInProgress = true
        
        while (syncInProgress && webSocket != null) {
            try {
                val delay = 5000L + kotlin.random.Random.nextLong(1000)
                delay(delay)
                
                val tik = ClockSyncTik(t1 = currentTimeMs())
                val payload = WSPayload(
                    kind = WSKind.SYNC.value,
                    msgType = WSClockSync.TIK.value,
                    body = Json.encodeToJsonElement(tik)
                )
                sendMessage(payload)
                lastSyncReportTime = currentTimeMs()
            } catch (e: Exception) {
                Log.e(TAG, "Error in sync scheduler", e)
                break
            }
        }
    }
    
    private suspend fun sendSyncReport(theta: Float, rtt: Float) {
        val report = ClockSyncReport(
            theta = kotlin.math.round(theta * 1000) / 1000,
            rtt = kotlin.math.round(rtt * 1000) / 1000
        )
        val payload = WSPayload(
            kind = WSKind.SYNC.value,
            msgType = WSClockSync.SYNC_REPORT.value,
            body = Json.encodeToJsonElement(report)
        )
        sendMessage(payload)
    }
    
    suspend fun sendAction(action: String, id: String, triggerTime: Long? = null) {
        val target = WSActionTarget(id = id, triggerTime = triggerTime)
        val payload = WSPayload(
            kind = WSKind.ACTION.value,
            msgType = action,
            body = Json.encodeToJsonElement(target)
        )
        sendMessage(payload)
    }
    
    suspend fun sendStateReport(state: String, duration: Int) {
        val report = StateReport(id = sessionId, state = state, duration = duration)
        val payload = WSPayload(
            kind = WSKind.EVENT.value,
            msgType = WSEvents.SESSION_STATE_REPORT.value,
            body = Json.encodeToJsonElement(report)
        )
        sendMessage(payload)
    }
    
    private suspend fun sendMessage(payload: WSPayload) {
        try {
            val json = Json.encodeToString(payload)
            webSocket?.send(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
        }
    }
    
    private fun currentTimeMs(): Long = System.currentTimeMillis()
    
    companion object {
        private const val TAG = "WebSocketManager"
    }
}
```

### 3. **HTTP Client for REST API** (Kotlin)

```kotlin name=RestClient.kt
package com.example.voicerecorder.network

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class RestClient(private val context: Context) {
    
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    suspend fun stageSession(
        baseUrl: String,
        name: String,
        ip: String,
        device: String,
        battery: Int
    ): Result<SessionMetadata> = withContext(Dispatchers.IO) {
        try {
            val meta = SessionMetadata(
                id = "placeholder",
                name = name,
                ip = ip,
                battery = battery,
                device = device
            )
            
            val request = SessionStageRequestMsg(body = meta)
            val jsonBody = json.encodeToString(SessionStageRequestMsg.serializer(), request)
            
            val httpRequest = okhttp3.Request.Builder()
                .url("$baseUrl/sessions")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = okHttpClient.newCall(httpRequest).execute()
            
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext Result.failure(
                    Exception("Empty response body")
                )
                val msg = json.decodeFromString<SessionStageResponseMsg>(body)
                Result.success(msg.body)
            } else {
                Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun getServerInfo(baseUrl: String): Result<ServerInfo> = withContext(Dispatchers.IO) {
        try {
            val httpRequest = okhttp3.Request.Builder()
                .url("$baseUrl/dashboard")
                .get()
                .build()
            
            val response = okHttpClient.newCall(httpRequest).execute()
            
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext Result.failure(
                    Exception("Empty response body")
                )
                val info = json.decodeFromString<ServerInfo>(body)
                Result.success(info)
            } else {
                Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun getActiveSessions(baseUrl: String): Result<List<SessionMetadata>> = 
        withContext(Dispatchers.IO) {
        try {
            val httpRequest = okhttp3.Request.Builder()
                .url("$baseUrl/sessions")
                .get()
                .build()
            
            val response = okHttpClient.newCall(httpRequest).execute()
            
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext Result.failure(
                    Exception("Empty response body")
                )
                val sessions = json.decodeFromString<List<SessionMetadata>>(body)
                Result.success(sessions)
            } else {
                Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
```

### 4. **Server Discovery (mDNS)** (Kotlin)

```kotlin name=ServerDiscovery.kt
package com.example.voicerecorder.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlin.coroutines.suspendCancellableCoroutine

data class DiscoveredServer(
    val name: String,
    val ip: String,
    val port: Int,
    val baseUrl: String
)

class ServerDiscovery(private val context: Context) {
    
    private val nsdManager: NsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val discoveredServers = mutableMapOf<String, DiscoveredServer>()
    
    suspend fun discoverServers(timeoutMs: Long = 3000): List<DiscoveredServer> {
        return suspendCancellableCoroutine { continuation ->
            discoveredServers.clear()
            
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "Discovery start failed: $errorCode")
                    continuation.resume(emptyList())
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
            
            nsdManager.discoverServices("_vocalink._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            
            // Stop discovery after timeout
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    nsdManager.stopServiceDiscovery(listener)
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping discovery", e)
                }
                continuation.resume(discoveredServers.values.toList())
            }, timeoutMs)
            
            continuation.invokeOnCancellation {
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
                
                nsdManager.stopResolution(this)
            }
        }
        
        nsdManager.resolveService(serviceInfo, listener)
    }
    
    companion object {
        private const val TAG = "ServerDiscovery"
    }
}
```

### 5. **Session Manager** (Kotlin)

```kotlin name=SessionManager.kt
package com.example.voicerecorder.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.roundToInt

class SessionManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    
    private val restClient = RestClient(context)
    private val serverDiscovery = ServerDiscovery(context)
    private val wsManager = WebSocketManager(scope)
    
    private var sessionMetadata: SessionMetadata? = null
    private var serverBaseUrl: String = ""
    
    var onSessionReady: ((SessionMetadata) -> Unit)? = null
    var onStateChanged: ((String, Int) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    
    suspend fun initialize(sessionName: String): Boolean = withContext(Dispatchers.Main) {
        return@withContext try {
            // Stage session on REST API
            val result = restClient.stageSession(
                baseUrl = serverBaseUrl,
                name = sessionName,
                ip = getLocalIp(),
                device = "mobile",
                battery = getBatteryLevel()
            )
            
            sessionMetadata = result.getOrNull() ?: run {
                onError?.invoke("Failed to stage session: ${result.exceptionOrNull()?.message}")
                return@withContext false
            }
            
            // Connect WebSocket
            wsManager.onSessionActivated = { meta ->
                sessionMetadata = meta
                onSessionReady?.invoke(meta)
            }
            
            wsManager.onStateChanged = { state, duration ->
                onStateChanged?.invoke(state, duration)
            }
            
            wsManager.onError = { error ->
                onError?.invoke(error)
            }
            
            val sessionId = sessionMetadata?.id ?: return@withContext false
            wsManager.connect(serverBaseUrl, sessionId, sessionName)
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing session", e)
            onError?.invoke(e.message ?: "Unknown error")
            false
        }
    }
    
    suspend fun discoverAndSelectServer(): Boolean = withContext(Dispatchers.Default) {
        return@withContext try {
            val servers = serverDiscovery.discoverServers()
            
            if (servers.isEmpty()) {
                // Fallback to localhost
                serverBaseUrl = "http://localhost:6210"
                return@withContext true
            }
            
            // Use first discovered server
            serverBaseUrl = servers[0].baseUrl
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Error discovering servers", e)
            onError?.invoke("Server discovery failed: ${e.message}")
            false
        }
    }
    
    suspend fun startRecording() {
        wsManager.sendAction(WSActions.START.value, sessionMetadata?.id ?: return)
    }
    
    suspend fun stopRecording() {
        wsManager.sendAction(WSActions.STOP.value, sessionMetadata?.id ?: return)
    }
    
    suspend fun pauseRecording() {
        wsManager.sendAction(WSActions.PAUSE.value, sessionMetadata?.id ?: return)
    }
    
    suspend fun resumeRecording() {
        wsManager.sendAction(WSActions.RESUME.value, sessionMetadata?.id ?: return)
    }
    
    suspend fun cancelRecording() {
        wsManager.sendAction(WSActions.CANCEL.value, sessionMetadata?.id ?: return)
    }
    
    suspend fun reportState(state: String, duration: Int) {
        wsManager.sendStateReport(state, duration)
    }
    
    fun disconnect() {
        wsManager.disconnect()
    }
    
    private fun getLocalIp(): String {
        return try {
            val networkInterfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (ni in networkInterfaces) {
                if (ni.isLoopback || !ni.isUp) continue
                for (addr in ni.interfaceAddresses) {
                    if (addr.address !is java.net.Inet4Address) continue
                    return addr.address.hostAddress ?: continue
                }
            }
            "127.0.0.1"
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }
    
    private fun getBatteryLevel(): Int {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        } catch (e: Exception) {
            50
        }
    }
    
    companion object {
        private const val TAG = "SessionManager"
    }
}
```

### 6. **Recording Activity Integration** (Kotlin)

```kotlin name=RecorderActivity.kt
package com.example.voicerecorder

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.voicerecorder.network.SessionManager
import com.example.voicerecorder.network.SessionStates
import kotlinx.coroutines.launch

class RecorderActivity : AppCompatActivity() {
    
    private lateinit var sessionManager: SessionManager
    private val TAG = "RecorderActivity"
    
    // UI References (assuming existing layout)
    private lateinit var statusText: TextView
    private lateinit var durationText: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var pauseBtn: Button
    
    private var currentState = SessionStates.STOPPED.value
    private var currentDuration = 0
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recorder)
        
        // Initialize views
        statusText = findViewById(R.id.status_text)
        durationText = findViewById(R.id.duration_text)
        startBtn = findViewById(R.id.btn_start)
        stopBtn = findViewById(R.id.btn_stop)
        pauseBtn = findViewById(R.id.btn_pause)
        
        // Setup button listeners
        startBtn.setOnClickListener { onStartClicked() }
        stopBtn.setOnClickListener { onStopClicked() }
        pauseBtn.setOnClickListener { onPauseClicked() }
        
        // Initialize SessionManager
        sessionManager = SessionManager(this, lifecycleScope)
        
        sessionManager.onSessionReady = { meta ->
            Log.d(TAG, "Session ready: ${meta.id}")
            updateUI("Connected to server", meta.name)
        }
        
        sessionManager.onStateChanged = { state, duration ->
            currentState = state
            currentDuration = duration
            updateUI(state, formatDuration(duration))
        }
        
        sessionManager.onError = { error ->
            Log.e(TAG, "Session error: $error")
            updateUI("ERROR: $error", "")
        }
        
        // Initialize connection
        lifecycleScope.launch {
            sessionManager.discoverAndSelectServer()
            sessionManager.initialize("Mobile Recorder")
        }
    }
    
    private fun onStartClicked() {
        lifecycleScope.launch {
            sessionManager.startRecording()
        }
    }
    
    private fun onStopClicked() {
        lifecycleScope.launch {
            sessionManager.stopRecording()
        }
    }
    
    private fun onPauseClicked() {
        lifecycleScope.launch {
            if (currentState == SessionStates.RUNNING.value) {
                sessionManager.pauseRecording()
            } else if (currentState == SessionStates.PAUSED.value) {
                sessionManager.resumeRecording()
            }
        }
    }
    
    private fun updateUI(status: String, info: String) {
        runOnUiThread {
            statusText.text = status
            durationText.text = info
        }
    }
    
    private fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, secs)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        sessionManager.disconnect()
    }
}
```

### 7. **Build Gradle Dependencies** (Kotlin/Gradle)

```gradle name=build.gradle.kts
dependencies {
    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    
    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.11.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    
    // Android
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
```

### 8. **AndroidManifest Permissions** (XML)

```xml name=AndroidManifest.xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.BATTERY_STATS" />
```

---

## Implementation Checklist

✅ **Primitives** - All data models matching backend  
✅ **WebSocket Manager** - Handles all message types, clock sync  
✅ **REST Client** - Session staging, server info queries  
✅ **Server Discovery** - mDNS service discovery  
✅ **Session Manager** - Orchestrates everything  
✅ **Activity Integration** - UI binding and lifecycle  
✅ **Dependencies** - All required libraries  
