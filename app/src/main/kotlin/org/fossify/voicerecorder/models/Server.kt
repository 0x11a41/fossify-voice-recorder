package org.fossify.voicerecorder.models

data class Server(val name: String, val ip: String, var isConnected: Boolean = false)
