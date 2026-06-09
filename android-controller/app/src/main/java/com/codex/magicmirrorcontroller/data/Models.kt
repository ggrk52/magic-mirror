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
    val layout: MirrorModuleLayout,
)

data class MirrorModuleLayout(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
)

data class MirrorModuleLayoutUpdate(
    val id: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
)

data class MirrorPhotoOverlay(
    val id: String,
    val mimeType: String,
    val sizeBytes: Long,
    val uploadedAt: String,
    val expiresAt: String,
    val durationSeconds: Int,
)

data class MirrorState(
    val displayState: String,
    val displayMode: String,
    val layoutEditMode: Boolean,
    val lastReloadedAt: String?,
    val photoOverlay: MirrorPhotoOverlay?,
    val modules: List<MirrorModule>,
)

data class MirrorDiagnostics(
    val status: String,
    val version: String?,
    val uptimeSeconds: Long,
    val socketCount: Int,
    val staticCacheEntries: Int,
    val memory: MirrorDiagnosticsMemory,
    val cpuTemp: Double?,
    val mirror: MirrorDiagnosticsMirror,
    val pairing: MirrorDiagnosticsPairing,
    val latencyMs: Long,
)


data class MirrorDiagnosticsMemory(
    val rss: Long,
    val heapUsed: Long,
    val heapTotal: Long,
)

data class MirrorDiagnosticsMirror(
    val displayState: String,
    val displayMode: String,
    val layoutEditMode: Boolean,
    val moduleCount: Int,
    val visibleModuleCount: Int,
    val photoOverlayActive: Boolean,
)

data class MirrorDiagnosticsPairing(
    val controllerConnected: Boolean,
    val controllerConnectedAt: String?,
)
