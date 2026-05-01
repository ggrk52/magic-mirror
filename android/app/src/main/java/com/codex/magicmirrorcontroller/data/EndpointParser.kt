package com.codex.magicmirrorcontroller.data

import java.net.URI

data class ParsedEndpoint(
    val host: String,
    val port: Int,
)

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
