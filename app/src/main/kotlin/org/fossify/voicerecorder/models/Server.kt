package org.fossify.voicerecorder.models

data class Server(
    val name: String,
    val ip: String,
    val port: Int,
    var isConnected: Boolean = false
)
