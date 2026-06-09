package com.codex.magicmirrorcontroller.data

import java.net.URI

data class ParsedEndpoint(
    val host: String,
    val port: Int,
)

fun isLanOrLocalHost(host: String): Boolean {
    val normalizedHost = host.trim().trim('[', ']').lowercase()

    if (
        normalizedHost == "localhost" ||
        normalizedHost.endsWith(".local") ||
        normalizedHost == "::1" ||
        (
            normalizedHost.contains(":") &&
                (
                    normalizedHost.startsWith("fe80:") ||
                        normalizedHost.startsWith("fc") ||
                        normalizedHost.startsWith("fd")
                    )
            )
    ) {
        return true
    }

    val parts = normalizedHost.split(".").mapNotNull { it.toIntOrNull() }
    if (parts.size != 4 || parts.any { it !in 0..255 }) {
        return false
    }

    return parts[0] == 10 ||
        parts[0] == 127 ||
        (parts[0] == 169 && parts[1] == 254) ||
        (parts[0] == 172 && parts[1] in 16..31) ||
        (parts[0] == 192 && parts[1] == 168)
}

fun parseEndpointInput(hostInput: String, portInput: String): ParsedEndpoint? {
    val rawHost = hostInput.trim()
    val fallbackPort = portInput.toIntOrNull()

    if (rawHost.isBlank()) {
        return null
    }

    return parseUriEndpoint(rawHost, fallbackPort)
        ?: parseBareIpv6Endpoint(rawHost, fallbackPort)
}

private fun parseUriEndpoint(hostInput: String, fallbackPort: Int?): ParsedEndpoint? {
    val candidate = if (hostInput.contains("://")) {
        hostInput
    } else {
        "http://$hostInput"
    }

    return try {
        val uri = URI(candidate)
        val host = uri.host?.trim()?.trim('[', ']')
        val port = if (uri.port > 0) uri.port else fallbackPort

        if (host.isNullOrBlank() || port == null) {
            null
        } else {
            ParsedEndpoint(host = host, port = port)
        }
    } catch (_: Exception) {
        null
    }
}

private fun parseBareIpv6Endpoint(hostInput: String, fallbackPort: Int?): ParsedEndpoint? {
    val host = hostInput
        .substringAfter("://")
        .substringBefore("/")
        .trim()
        .trim('[', ']')

    if (host.count { it == ':' } < 2 || fallbackPort == null) {
        return null
    }

    return ParsedEndpoint(host = host, port = fallbackPort)
}
