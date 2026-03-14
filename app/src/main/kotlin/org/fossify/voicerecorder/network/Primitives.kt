package org.fossify.voicerecorder.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

const val VERSION = "v0.81-alpha"
const val PORT = 6210
const val BROADCAST = "all"

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
    DROPPED,
    @SerialName("rec_stage")
    REC_STAGE,
    @SerialName("rec_staged")
    REC_STAGED,
    @SerialName("rec_amend")
    REC_AMEND
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
    GET_STATE,
    @SerialName("start_all")
    START_ALL,
    @SerialName("stop_all")
    STOP_ALL,
    @SerialName("pause_all")
    PAUSE_ALL,
    @SerialName("resume_all")
    RESUME_ALL,
    @SerialName("cancel_all")
    CANCEL_ALL,
    @SerialName("rec_rename")
    REC_RENAME
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
data class RecStageInfo(
    val sessionId: String,
    val recName: String,
    val duration: Int,
    val sizeBytes: Long
)

@Serializable
enum class RecStates {
    @SerialName("ok")
    OK,
    @SerialName("na")
    NA,
    @SerialName("working")
    WORKING
}

@Serializable
data class RecMetadata(
    val rid: String,
    val recName: String,
    val sessionId: String,
    val speaker: String,
    val device: String,
    val duration: Float,
    val sizeBytes: Long,
    val createdAt: Long,
    val original: RecStates = RecStates.NA,
    val enhanced: RecStates = RecStates.NA,
    val transcript: RecStates = RecStates.NA,
    val merged: List<String>? = null
)

@Serializable
data class TranscriptSegment(
    val start: Float,
    val end: Float,
    val text: String
)

@Serializable
data class TranscriptResult(
    val rid: String,
    val language: String,
    val duration: Float,
    val segments: List<TranscriptSegment>
)

@Serializable
data class MergeRequest(
    val rids: List<String>
)

object EnhanceProps {
    const val AMPLIFY = 1
    const val REDUCE_NOISE = 2
    const val STUDIO_FILTER = 4
}

@Serializable
data class QRData(
    val type: String = "vocal_link_server",
    val name: String,
    val ip: String,
    val port: Int = PORT
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
