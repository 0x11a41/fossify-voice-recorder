package org.fossify.voicerecorder.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

const val VERSION = "0.72-alpha"

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
enum class WSKind {
    @SerialName("action")
    ACTION,
    @SerialName("event")
    EVENT,
    @SerialName("error")
    ERROR,
    @SerialName("sync")
    SYNC
}

@Serializable
enum class WSErrors {
    @SerialName("invalid_kind")
    INVALID_KIND,
    @SerialName("invalid_event")
    INVALID_EVENT,
    @SerialName("invalid_action")
    INVALID_ACTION,
    @SerialName("invalid_body")
    INVALID_BODY,
    @SerialName("action_not_allowed")
    ACTION_NOT_ALLOWED,
    @SerialName("session_not_found")
    SESSION_NOT_FOUND
}

@Serializable
enum class WSEvents {
    @SerialName("dashboard_init")
    DASHBOARD_INIT,
    @SerialName("dashboard_rename")
    DASHBOARD_RENAME,
    @SerialName("session_update")
    SESSION_UPDATE,
    @SerialName("session_activate")
    SESSION_ACTIVATE,
    @SerialName("session_activated")
    SESSION_ACTIVATED,
    @SerialName("success")
    SUCCESS,
    @SerialName("failed")
    FAILED,
    @SerialName("session_state_report")
    SESSION_STATE_REPORT,
    @SerialName("started")
    STARTED,
    @SerialName("stopped")
    STOPPED,
    @SerialName("paused")
    PAUSED,
    @SerialName("resumed")
    RESUMED,
    @SerialName("dropped")
    DROPPED
}

@Serializable
enum class WSActions {
    @SerialName("start")
    START,
    @SerialName("stop")
    STOP,
    @SerialName("pause")
    PAUSE,
    @SerialName("resume")
    RESUME,
    @SerialName("cancel")
    CANCEL,
    @SerialName("drop")
    DROP,
    @SerialName("get_state")
    GET_STATE
}

@Serializable
enum class WSClockSync {
    @SerialName("tik")
    TIK,
    @SerialName("tok")
    TOK,
    @SerialName("sync_report")
    SYNC_REPORT
}

@Serializable
enum class SessionStates {
    @SerialName("stopped")
    STOPPED,
    @SerialName("running")
    RUNNING,
    @SerialName("paused")
    PAUSED
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
data class WSEventTarget(val id: String)

@Serializable
data class Rename(val name: String)

@Serializable
data class StateReport(
    val id: String,
    val state: SessionStates,
    val duration: Int = 0
)

@Serializable
data class QRData(
    val type: String = "vocal_link_server",
    val name: String,
    val ip: String
)

@Serializable
data class WSPayload(
    val kind: WSKind,
    @SerialName("msgType")
    val msgType: String,
    val body: JsonElement? = null
)

// ---------- Helpers for sending ----------

fun createEventPayload(event: WSEvents, body: JsonElement? = null) = WSPayload(
    kind = WSKind.EVENT,
    msgType = event.name.lowercase(),
    body = body
)

fun createSyncPayload(type: WSClockSync, body: JsonElement) = WSPayload(
    kind = WSKind.SYNC,
    msgType = type.name.lowercase(),
    body = body
)
