package org.fossify.voicerecorder.network

import kotlinx.serialization.json.Json

object JsonConfig {
    val WS_JSON = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "kind"
    }
}
