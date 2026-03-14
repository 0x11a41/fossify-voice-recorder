package org.fossify.voicerecorder.network

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import org.fossify.voicerecorder.network.JsonConfig.WS_JSON

class RestClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
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

    suspend fun uploadRecording(
        baseUrl: String,
        rid: String,
        context: Context,
        uri: Uri
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(Exception("Could not open input stream"))

            // Create a temp file to use with OkHttp's asRequestBody
            val tempFile = File(context.cacheDir, "upload_$rid")
            FileOutputStream(tempFile).use { output ->
                inputStream.copyTo(output)
            }

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    tempFile.name,
                    tempFile.asRequestBody("audio/*".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url("$baseUrl/recordings/$rid")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                tempFile.delete()
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    httpError(response.code, response.message)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
