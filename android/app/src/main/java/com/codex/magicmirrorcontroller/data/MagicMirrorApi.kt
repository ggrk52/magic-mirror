package com.codex.magicmirrorcontroller.data

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

class MagicMirrorApi {
    fun checkHealth(config: ServerConfig) {
        request(
            method = "GET",
            url = buildUrl(config, "/api/health"),
            token = null,
        )
    }

    fun fetchState(config: ServerConfig): MirrorState {
        val payload = request(
            method = "GET",
            url = buildUrl(config, "/api/mirror/state"),
            token = config.token,
        )

        return parseState(JSONObject(payload))
    }

    fun sendDisplayAction(config: ServerConfig, action: String): MirrorState {
        val payload = request(
            method = "POST",
            url = buildUrl(config, "/api/mirror/display"),
            token = config.token,
            body = JSONObject().put("action", action).toString(),
        )

        return parseState(JSONObject(payload))
    }

    fun setDisplayMode(config: ServerConfig, mode: String): MirrorState {
        val payload = request(
            method = "POST",
            url = buildUrl(config, "/api/mirror/mode"),
            token = config.token,
            body = JSONObject().put("mode", mode).toString(),
        )

        return parseState(JSONObject(payload))
    }

    fun setModuleVisibility(config: ServerConfig, moduleId: String, visible: Boolean): MirrorState {
        val payload = request(
            method = "POST",
            url = buildUrl(config, "/api/modules/$moduleId/visibility"),
            token = config.token,
            body = JSONObject().put("visible", visible).toString(),
        )

        return parseState(JSONObject(payload))
    }

    fun refreshModule(config: ServerConfig, moduleId: String): MirrorState {
        val payload = request(
            method = "POST",
            url = buildUrl(config, "/api/modules/$moduleId/refresh"),
            token = config.token,
        )

        return parseState(JSONObject(payload))
    }

    fun refreshAll(config: ServerConfig): MirrorState {
        val payload = request(
            method = "POST",
            url = buildUrl(config, "/api/modules/refresh-all"),
            token = config.token,
        )

        return parseState(JSONObject(payload))
    }

    fun markPairingComplete(config: ServerConfig) {
        request(
            method = "POST",
            url = buildUrl(config, "/api/pairing/complete"),
            token = config.token,
        )
    }

    fun sendSetupToken(host: String, port: Int, token: String) {
        request(
            method = "POST",
            url = buildUrl(host, port, "/api/setup/token"),
            token = null,
            body = JSONObject().put("token", token).toString(),
        )
    }

    fun sendSetupWifi(host: String, port: Int, ssid: String, password: String) {
        request(
            method = "POST",
            url = buildUrl(host, port, "/api/setup/wifi"),
            token = null,
            body = JSONObject()
                .put("ssid", ssid)
                .put("password", password)
                .toString(),
        )
    }

    private fun request(
        method: String,
        url: String,
        token: String?,
        body: String? = null,
    ): String {
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 4000
                readTimeout = 4000
                doInput = true
                setRequestProperty("Accept", "application/json")

                if (token != null) {
                    setRequestProperty("Authorization", "Bearer $token")
                }

                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.bufferedWriter().use { writer ->
                        writer.write(body)
                    }
                }
            }

            try {
                val statusCode = connection.responseCode
                val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
                val payload = stream?.use { input ->
                    BufferedReader(InputStreamReader(input)).readText()
                }.orEmpty()

                if (statusCode !in 200..299) {
                    throw RuntimeException(parseApiError(payload, statusCode))
                }

                payload
            } finally {
                connection.disconnect()
            }
        } catch (error: MalformedURLException) {
            throw RuntimeException("Некорректный адрес сервера. Введи IP без лишнего пути, например 192.168.1.75.")
        } catch (error: UnknownHostException) {
            throw RuntimeException("Сервер не найден. Проверь IP-адрес и Wi‑Fi сеть.")
        } catch (error: ConnectException) {
            throw RuntimeException("Не удалось подключиться. Проверь, что сервер запущен и порт открыт.")
        } catch (timeout: SocketTimeoutException) {
            throw RuntimeException("Сервер не ответил. Проверь, что телефон в той же локальной сети.")
        }
    }

    private fun parseApiError(payload: String, statusCode: Int): String {
        return try {
            val message = JSONObject(payload).optString("message")
            if (message.isNotBlank()) {
                message
            } else {
                "Запрос завершился с кодом $statusCode"
            }
        } catch (_: Exception) {
            "Запрос завершился с кодом $statusCode"
        }
    }

    private fun buildUrl(config: ServerConfig, path: String): String {
        return buildUrl(config.host, config.port, path)
    }

    private fun buildUrl(rawHost: String, port: Int, path: String): String {
        val host = if (rawHost.contains(":") && !rawHost.startsWith("[")) {
            "[$rawHost]"
        } else {
            rawHost
        }

        return "http://$host:$port$path"
    }

    private fun parseState(json: JSONObject): MirrorState {
        val modules = json.optJSONArray("modules") ?: JSONArray()

        return MirrorState(
            displayState = json.optString("displayState", "off"),
            displayMode = json.optString("displayMode", "mirror"),
            lastReloadedAt = json.optString("lastReloadedAt").takeIf { it.isNotBlank() },
            modules = buildList {
                for (index in 0 until modules.length()) {
                    val item = modules.getJSONObject(index)
                    add(
                        MirrorModule(
                            id = item.getString("id"),
                            title = item.getString("title"),
                            visible = item.optBoolean("visible"),
                            refreshable = item.optBoolean("refreshable"),
                            lastUpdatedAt = item.optString("lastUpdatedAt").takeIf { it.isNotBlank() },
                        ),
                    )
                }
            },
        )
    }
}
