package com.codex.magicmirrorcontroller.data

import org.json.JSONObject

private const val PAIRING_TYPE = "magic-mirror-pair"

fun parsePairingPayload(rawValue: String): PairingPayload? {
    return try {
        val json = JSONObject(rawValue)

        if (json.optString("type") != PAIRING_TYPE || json.optInt("version") != 1) {
            return null
        }

        val token = json.optString("token").trim()
        val port = json.optInt("port", 8080)
        val hostsJson = json.optJSONArray("hosts")
        val hosts = buildList {
            if (hostsJson != null) {
                for (index in 0 until hostsJson.length()) {
                    val host = hostsJson.optString(index).trim().trim('[', ']')
                    if (host.isNotBlank()) {
                        add(host)
                    }
                }
            }
        }

        if (token.isBlank()) {
            null
        } else {
            PairingPayload(
                token = token,
                port = port,
                hosts = hosts,
                service = json.optString("service").takeIf { it.isNotBlank() },
            )
        }
    } catch (_: Exception) {
        null
    }
}
