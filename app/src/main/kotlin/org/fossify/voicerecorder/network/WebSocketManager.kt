package org.fossify.voicerecorder.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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
    var onStateChanged: ((SessionStates, Int) -> Unit)? = null
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
        scope.launch {
            sendSessionActivation()
            onConnected?.invoke()
        }
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        scope.launch {
            try {
                when (val msg = WS_JSON.decodeFromString<WSMessage>(text)) {
                    is WSEventMessage -> handleEvent(msg)
                    is WSClockSyncMessage -> handleSync(msg)
                    is WSErrorMessage -> onError?.invoke(msg.message ?: msg.error.name)
                    else -> {}
                }

            } catch (e: Exception) {
                Log.e(TAG, "Parse error", e)
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
        send(WSEventMessage(
                event = WSEvents.SESSION_ACTIVATE,
                body = WS_JSON.encodeToJsonElement(WSEventTarget(sessionId))
        ))
    }

    suspend fun sendAction(action: WSActions, id: String, triggerTime: Long? = null) {
        send(WSActionMessage(
                action = action,
                body = WSActionTarget(id, triggerTime)
        ))
    }

    suspend fun sendStateReport(state: SessionStates, duration: Int) {
        send(WSEventMessage(
                event = WSEvents.SESSION_STATE_REPORT,
                body = WS_JSON.encodeToJsonElement(StateReport(sessionId, state, duration))
        ))
    }

    private suspend fun send(message: WSMessage) {
        try {
            webSocket?.send(WS_JSON.encodeToString(message))
        } catch (e: Exception) {
            Log.e(TAG, "Send error", e)
        }
    }

    // ---------- Receive Handlers ----------

    private fun handleEvent(msg: WSEventMessage) {
        val body = msg.body ?: return

        when (msg.event) {
            WSEvents.SESSION_ACTIVATED -> {
                val meta = WS_JSON.decodeFromJsonElement<SessionMetadata>(body)
                onSessionActivated?.invoke(meta)
                scope.launch { startSyncScheduler() }
            }

            WSEvents.SESSION_UPDATE -> {
                val meta = WS_JSON.decodeFromJsonElement<SessionMetadata>(body)
                onSessionUpdate?.invoke(meta)
            }

            WSEvents.STARTED -> onStateChanged?.invoke(SessionStates.RUNNING, 0)
            WSEvents.STOPPED -> onStateChanged?.invoke(SessionStates.STOPPED, 0)
            WSEvents.PAUSED -> onStateChanged?.invoke(SessionStates.PAUSED, 0)
            WSEvents.RESUMED -> onStateChanged?.invoke(SessionStates.RUNNING, 0)

            WSEvents.SESSION_STATE_REPORT -> {
                val report = WS_JSON.decodeFromJsonElement<StateReport>(body)
                onStateChanged?.invoke(report.state, report.duration)
            }

            WSEvents.DROPPED -> {
                val target = WS_JSON.decodeFromJsonElement<WSEventTarget>(body)
                onSessionDropped?.invoke(target.id)
            }

            else -> {}
        }
    }

    private fun handleSync(msg: WSClockSyncMessage) {
        when (msg.type) {

            WSClockSync.TOK -> scope.launch {
                val tok = WS_JSON.decodeFromJsonElement<ClockSyncTok>(msg.body)
                val t4 = now()

                val rtt = (t4 - tok.t1) - (tok.t3 - tok.t2)
                val theta = ((tok.t2 - tok.t1) + (tok.t3 - t4)) / 2f

                send(
                    WSClockSyncMessage(
                        type = WSClockSync.SYNC_REPORT,
                        body = WS_JSON.encodeToJsonElement(
                            ClockSyncReport(theta, rtt.toFloat())
                        )
                    )
                )
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
            send(WSClockSyncMessage(
                    type = WSClockSync.TIK,
                    body = WS_JSON.encodeToJsonElement(
                        ClockSyncTik(now())
                    )
            ))
        }
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val TAG = "WebSocketManager"
    }
}
