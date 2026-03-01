package org.fossify.voicerecorder.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import org.fossify.voicerecorder.network.JsonConfig.WS_JSON

class WebSocketManager(private val scope: CoroutineScope) : WebSocketListener() {
    private var webSocket: WebSocket? = null
    private var sessionId: String = ""
    private var sessionName: String = ""
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private var syncInProgress = false
    var onSessionActivated: ((SessionMetadata) -> Unit)? = null
    var onSessionUpdate: ((SessionMetadata) -> Unit)? = null
    var onSessionDropped: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    // Action Callbacks
    var onStart: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onPause: (() -> Unit)? = null
    var onResume: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var onGetState: (() -> Unit)? = null

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
        scope.launch {
            sendSessionActivation()
            onConnected?.invoke()
        }
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        scope.launch {
            try {
                val payload = WS_JSON.decodeFromString<WSPayload>(text)
                
                when (payload.kind) {
                    WSKind.EVENT -> {
                        val event = try { WSEvents.valueOf(payload.msgType.uppercase()) } catch (_: Exception) { null }
                        if (event != null) handleEvent(event, payload.body)
                    }
                    WSKind.SYNC -> {
                        val type = try { WSClockSync.valueOf(payload.msgType.uppercase()) } catch (_: Exception) { null }
                        if (type != null) handleSync(type, payload.body ?: JsonNull)
                    }
                    WSKind.ACTION -> {
                        val action = try { WSActions.valueOf(payload.msgType.uppercase()) } catch (_: Exception) { null }
                        if (action != null) handleAction(action)
                    }
                    WSKind.ERROR -> {
                        val error = try { WSErrors.valueOf(payload.msgType.uppercase()) } catch (_: Exception) { null }
                        onError?.invoke(error?.name ?: payload.msgType)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Parse error: ${e.message}", e)
            }
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
        onError?.invoke(t.message ?: "WebSocket failure")
        onDisconnected?.invoke()
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        syncInProgress = false
        onDisconnected?.invoke()
    }

    // ---------- Send ----------

    private suspend fun sendSessionActivation() {
        sendEvent(WSEvents.SESSION_ACTIVATE, sessionId)
    }

    suspend fun sendAction(action: WSActions, id: String, triggerTime: Long? = null) {
        val body = WS_JSON.encodeToJsonElement(WSActionTarget(id, triggerTime))
        send(WSKind.ACTION, action.name.lowercase(), body)
    }

    suspend fun sendStateReport(state: SessionStates, duration: Int) {
        val body = WS_JSON.encodeToJsonElement(StateReport(sessionId, state, duration))
        send(WSKind.EVENT, WSEvents.SESSION_STATE_REPORT.name.lowercase(), body)
    }

    suspend fun sendEvent(event: WSEvents, id: String) {
        val body = WS_JSON.encodeToJsonElement(WSEventTarget(id))
        send(WSKind.EVENT, event.name.lowercase(), body)
    }

    private fun send(kind: WSKind, msgType: String, body: JsonElement? = null) {
        try {
            val payload = WSPayload(kind, msgType, body)
            webSocket?.send(WS_JSON.encodeToString(payload))
        } catch (e: Exception) {
            Log.e(TAG, "Send error", e)
        }
    }

    // ---------- Receive Handlers ----------

    private fun handleEvent(event: WSEvents, body: JsonElement?) {
        if (body == null || body is JsonNull) return

        when (event) {
            WSEvents.SESSION_ACTIVATED -> {
                val meta = WS_JSON.decodeFromJsonElement<SessionMetadata>(body)
                onSessionActivated?.invoke(meta)
                scope.launch { startSyncScheduler() }
            }

            WSEvents.SESSION_UPDATE -> {
                val meta = WS_JSON.decodeFromJsonElement<SessionMetadata>(body)
                onSessionUpdate?.invoke(meta)
            }

            WSEvents.DROPPED -> {
                val target = WS_JSON.decodeFromJsonElement<WSEventTarget>(body)
                onSessionDropped?.invoke(target.id)
            }

            else -> {}
        }
    }

    private fun handleAction(action: WSActions) {
        when (action) {
            WSActions.START -> onStart?.invoke()
            WSActions.STOP -> onStop?.invoke()
            WSActions.PAUSE -> onPause?.invoke()
            WSActions.RESUME -> onResume?.invoke()
            WSActions.CANCEL -> onCancel?.invoke()
            WSActions.GET_STATE -> onGetState?.invoke()
            else -> {}
        }
    }

    private fun handleSync(type: WSClockSync, body: JsonElement) {
        when (type) {
            WSClockSync.TOK -> {
                val tok = WS_JSON.decodeFromJsonElement<ClockSyncTok>(body)
                val t4 = now()

                val rtt = (t4 - tok.t1) - (tok.t3 - tok.t2)
                val theta = ((tok.t2 - tok.t1) + (tok.t3 - t4)) / 2f

                val reportBody = WS_JSON.encodeToJsonElement(
                    ClockSyncReport(theta, rtt.toFloat())
                )
                send(WSKind.SYNC, WSClockSync.SYNC_REPORT.name.lowercase(), reportBody)
            }

            else -> {}
        }
    }

    // ---------- Sync ----------
    private suspend fun startSyncScheduler() {
        if (syncInProgress) return
        syncInProgress = true
        while (syncInProgress && webSocket != null) {
            delay(5000 + kotlin.random.Random.nextLong(1000))
            val tikBody = WS_JSON.encodeToJsonElement(ClockSyncTik(now()))
            send(WSKind.SYNC, WSClockSync.TIK.name.lowercase(), tikBody)
        }
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val TAG = "WebSocketManager"
    }
}
