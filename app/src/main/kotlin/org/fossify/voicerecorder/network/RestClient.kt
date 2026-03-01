package org.fossify.voicerecorder.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import org.fossify.voicerecorder.network.JsonConfig.WS_JSON

class RestClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun <T> httpError(code: Int, msg: String): Result<T> = Result.failure(Exception("HTTP $code: $msg"))
    private fun <T> emptyBodyError(): Result<T> = Result.failure(Exception("Empty response body"))

    suspend fun stageSession(
        baseUrl: String,
        name: String,
        ip: String,
        device: String,
        battery: Int
    ): Result<SessionMetadata> = withContext(Dispatchers.IO) {
        try {
            val meta = SessionMetadata(
                id = "", // server should assign
                name = name,
                ip = ip,
                battery = battery,
                device = device
            )

            val request = Request.Builder()
                .url("$baseUrl/sessions")
                .post(
                    WS_JSON.encodeToString(meta)
                        .toRequestBody("application/json".toMediaType())
                )
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful)
                    return@withContext httpError(response.code, response.message)

                val body = response.body.string()
                if (body.isEmpty())
                    return@withContext emptyBodyError()

                Result.success(WS_JSON.decodeFromString<SessionMetadata>(body))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
