package com.codex.magicmirrorcontroller.data

import android.util.Base64
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

    fun fetchDiagnostics(config: ServerConfig): MirrorDiagnostics {
        val startedAt = System.nanoTime()
        val payload = request(
            method = "GET",
            url = buildUrl(config, "/api/diagnostics"),
            token = config.token,
        )
        val latencyMs = ((System.nanoTime() - startedAt) / 1_000_000).coerceAtLeast(0)

        return parseDiagnostics(JSONObject(payload), latencyMs)
    }

    fun setLayoutEditMode(config: ServerConfig, active: Boolean): MirrorState {
        val payload = request(
            method = "POST",
            url = buildUrl(config, "/api/mirror/layout/edit"),
            token = config.token,
            body = JSONObject().put("active", active).toString(),
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

    fun updateModuleLayout(
        config: ServerConfig,
        modules: List<MirrorModuleLayoutUpdate>,
    ): MirrorState {
        val items = JSONArray()
        for (module in modules) {
            items.put(
                JSONObject()
                    .put("id", module.id)
                    .put("x", module.x)
                    .put("y", module.y)
                    .put("w", module.w)
                    .put("h", module.h),
            )
        }

        val payload = request(
            method = "POST",
            url = buildUrl(config, "/api/modules/layout"),
            token = config.token,
            body = JSONObject().put("modules", items).toString(),
        )

        return parseState(JSONObject(payload))
    }

    fun resetModuleLayout(config: ServerConfig): MirrorState {
        val payload = request(
            method = "POST",
            url = buildUrl(config, "/api/modules/layout/reset"),
            token = config.token,
        )

        return parseState(JSONObject(payload))
    }

    fun uploadPhoto(
        config: ServerConfig,
        imageBytes: ByteArray,
        mimeType: String,
        durationSeconds: Int,
    ): MirrorState {
        val imageData = "data:$mimeType;base64,${Base64.encodeToString(imageBytes, Base64.NO_WRAP)}"
        val payload = request(
            method = "POST",
            url = buildUrl(config, "/api/mirror/photo"),
            token = config.token,
            body = JSONObject()
                .put("imageData", imageData)
                .put("durationSeconds", durationSeconds)
                .toString(),
        )

        return parseState(JSONObject(payload))
    }

    fun clearPhoto(config: ServerConfig): MirrorState {
        val payload = request(
            method = "DELETE",
            url = buildUrl(config, "/api/mirror/photo"),
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
        val photoOverlay = json.optJSONObject("photoOverlay")?.let { photo ->
            MirrorPhotoOverlay(
                id = photo.getString("id"),
                mimeType = photo.getString("mimeType"),
                sizeBytes = photo.optLong("sizeBytes"),
                uploadedAt = photo.getString("uploadedAt"),
                expiresAt = photo.getString("expiresAt"),
                durationSeconds = photo.optInt("durationSeconds"),
            )
        }

        return MirrorState(
            displayState = json.optString("displayState", "off"),
            displayMode = json.optString("displayMode", "mirror"),
            layoutEditMode = json.optBoolean("layoutEditMode"),
            lastReloadedAt = json.optString("lastReloadedAt").takeIf { it.isNotBlank() },
            photoOverlay = photoOverlay,
            modules = buildList {
                for (index in 0 until modules.length()) {
                    val item = modules.getJSONObject(index)
                    val layout = item.optJSONObject("layout") ?: JSONObject()
                    add(
                        MirrorModule(
                            id = item.getString("id"),
                            title = item.getString("title"),
                            visible = item.optBoolean("visible"),
                            refreshable = item.optBoolean("refreshable"),
                            lastUpdatedAt = item.optString("lastUpdatedAt").takeIf { it.isNotBlank() },
                            layout = MirrorModuleLayout(
                                x = layout.optDouble("x", 0.0).toFloat(),
                                y = layout.optDouble("y", 0.0).toFloat(),
                                w = layout.optDouble("w", 42.0).toFloat(),
                                h = layout.optDouble("h", 12.0).toFloat(),
                            ),
                        ),
                    )
                }
            },
        )
    }

    private fun parseDiagnostics(json: JSONObject, latencyMs: Long): MirrorDiagnostics {
        val memory = json.optJSONObject("memory") ?: JSONObject()
        val mirror = json.optJSONObject("mirror") ?: JSONObject()
        val pairing = json.optJSONObject("pairing") ?: JSONObject()

        return MirrorDiagnostics(
            status = json.optString("status", "unknown"),
            version = json.optString("version").takeIf { it.isNotBlank() },
            uptimeSeconds = json.optLong("uptimeSeconds"),
            socketCount = json.optInt("socketCount"),
            staticCacheEntries = json.optInt("staticCacheEntries"),
            memory = MirrorDiagnosticsMemory(
                rss = memory.optLong("rss"),
                heapUsed = memory.optLong("heapUsed"),
                heapTotal = memory.optLong("heapTotal"),
            ),
            cpuTemp = json.optDouble("cpuTemp", Double.NaN).takeIf { !it.isNaN() },
            mirror = MirrorDiagnosticsMirror(
                displayState = mirror.optString("displayState", "unknown"),
                displayMode = mirror.optString("displayMode", "unknown"),
                layoutEditMode = mirror.optBoolean("layoutEditMode"),
                moduleCount = mirror.optInt("moduleCount"),
                visibleModuleCount = mirror.optInt("visibleModuleCount"),
                photoOverlayActive = mirror.optBoolean("photoOverlayActive"),
            ),
            pairing = MirrorDiagnosticsPairing(
                controllerConnected = pairing.optBoolean("controllerConnected"),
                controllerConnectedAt = pairing.optString("controllerConnectedAt").takeIf { it.isNotBlank() },
            ),
            latencyMs = latencyMs,
        )

    }
}
