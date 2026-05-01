package com.codex.magicmirrorcontroller.data

data class ServerConfig(
    val host: String,
    val port: Int,
    val token: String,
)

data class ConnectionFormState(
    val host: String = "",
    val port: String = "8080",
    val token: String = "",
)

data class SetupFormState(
    val host: String = "192.168.4.1",
    val port: String = "8080",
    val ssid: String = "",
    val password: String = "",
    val token: String = "magic-mirror-local-token",
)

data class SavedServerEndpoint(
    val host: String,
    val port: Int,
)

data class DiscoveredMirror(
    val name: String,
    val host: String,
    val port: Int,
    val instanceId: String?,
    val version: String?,
    val setupMode: Boolean,
)

data class PairingPayload(
    val token: String,
    val port: Int,
    val hosts: List<String>,
    val service: String?,
)

data class MirrorModule(
    val id: String,
    val title: String,
    val visible: Boolean,
    val refreshable: Boolean,
    val lastUpdatedAt: String?,
)

data class MirrorState(
    val displayState: String,
    val displayMode: String,
    val lastReloadedAt: String?,
    val modules: List<MirrorModule>,
)
