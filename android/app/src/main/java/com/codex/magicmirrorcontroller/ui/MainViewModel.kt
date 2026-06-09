package com.codex.magicmirrorcontroller.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.scale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import com.codex.magicmirrorcontroller.data.ConnectionFormState
import com.codex.magicmirrorcontroller.data.DiscoveredMirror
import com.codex.magicmirrorcontroller.data.MagicMirrorApi
import com.codex.magicmirrorcontroller.data.MagicMirrorDiscovery
import com.codex.magicmirrorcontroller.data.MirrorDiagnostics
import com.codex.magicmirrorcontroller.data.MirrorModule
import com.codex.magicmirrorcontroller.data.MirrorModuleLayout
import com.codex.magicmirrorcontroller.data.MirrorModuleLayoutUpdate
import com.codex.magicmirrorcontroller.data.MirrorState
import com.codex.magicmirrorcontroller.data.SavedServerEndpoint
import com.codex.magicmirrorcontroller.data.SecureTokenStore
import com.codex.magicmirrorcontroller.data.ServerConfig
import com.codex.magicmirrorcontroller.data.ServerConfigRepository
import com.codex.magicmirrorcontroller.data.SetupFormState
import com.codex.magicmirrorcontroller.data.parseEndpointInput
import com.codex.magicmirrorcontroller.data.parsePairingPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MainUiState(
    val formState: ConnectionFormState = ConnectionFormState(),
    val setupFormState: SetupFormState = SetupFormState(),
    val discoveredMirrors: List<DiscoveredMirror> = emptyList(),
    val mirrorState: MirrorState? = null,
    val diagnostics: MirrorDiagnostics? = null,
    val endpointLabel: String = "",
    val isConnected: Boolean = false,
    val isBusy: Boolean = false,
    val isScanning: Boolean = false,
    val diagnosticsRefreshing: Boolean = false,
    val initialized: Boolean = false,
    val manualExpanded: Boolean = false,
    val setupExpanded: Boolean = false,
    val qrScannerOpen: Boolean = false,
    val layoutEditorOpen: Boolean = false,
    val layoutDraft: Map<String, MirrorModuleLayout> = emptyMap(),
    val layoutSnapshot: Map<String, MirrorModuleLayout> = emptyMap(),
    val photoDurationMinutes: String = "5",
    val message: String? = null,
)

private const val MaxPhotoUploadBytes = 6 * 1024 * 1024
private const val MaxPhotoDimension = 1800

private class PreparedPhoto(
    val bytes: ByteArray,
    val mimeType: String,
)

class MainViewModel(
    private val api: MagicMirrorApi,
    private val repository: ServerConfigRepository,
    private val discovery: MagicMirrorDiscovery,
    private val tokenStore: SecureTokenStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var currentConfig: ServerConfig? = null

    fun initialize() {
        if (_uiState.value.initialized) return

        viewModelScope.launch {
            val savedEndpoint = repository.savedEndpoint.first()
            val savedToken = withContext(Dispatchers.IO) { tokenStore.readToken().orEmpty() }

            _uiState.update {
                it.copy(
                    initialized = true,
                    formState = savedEndpoint?.toFormState(it.formState, savedToken) ?: it.formState.copy(token = savedToken),
                    message = if (savedEndpoint != null && savedToken.isNotBlank()) {
                        "Нашёл сохранённую пару. Проверяю зеркало..."
                    } else {
                        "Запускаю авто-поиск зеркала в сети."
                    },
                )
            }

            startDiscovery()

            if (savedEndpoint != null && savedToken.isNotBlank()) {
                connectWithConfig(
                    ServerConfig(
                        host = savedEndpoint.host,
                        port = savedEndpoint.port,
                        token = savedToken,
                    ),
                    persistConfig = false,
                )
            }
        }
    }

    fun startDiscovery() {
        _uiState.update { it.copy(isScanning = true, message = "Ищем зеркало рядом...") }

        discovery.start(
            onMirrorFound = { mirror ->
                _uiState.update { state ->
                    val mirrors = (state.discoveredMirrors.filterNot {
                        it.host == mirror.host && it.port == mirror.port
                    } + mirror).sortedWith(compareBy<DiscoveredMirror> { it.name }.thenBy { it.host })

                    state.copy(
                        discoveredMirrors = mirrors,
                        isScanning = true,
                        message = "Нашёл ${mirrors.size} зеркал(о).",
                    )
                }
            },
            onStatus = { message, scanning ->
                _uiState.update { it.copy(message = message, isScanning = scanning) }
            },
        )
    }

    fun stopDiscovery() {
        discovery.stop()
        _uiState.update { it.copy(isScanning = false, message = "Авто-поиск остановлен.") }
    }

    fun toggleManual() {
        _uiState.update { it.copy(manualExpanded = !it.manualExpanded, message = null) }
    }

    fun toggleSetup() {
        _uiState.update { it.copy(setupExpanded = !it.setupExpanded, message = null) }
    }

    fun openQrScanner() {
        _uiState.update { it.copy(qrScannerOpen = true, message = "Наведи камеру на QR-код на экране зеркала.") }
    }

    fun closeQrScanner() {
        _uiState.update { it.copy(qrScannerOpen = false) }
    }

    fun updateHost(value: String) {
        _uiState.update { it.copy(formState = it.formState.copy(host = value), message = null) }
    }

    fun updatePort(value: String) {
        _uiState.update { it.copy(formState = it.formState.copy(port = value.filter(Char::isDigit)), message = null) }
    }

    fun updateToken(value: String) {
        _uiState.update { it.copy(formState = it.formState.copy(token = value), message = null) }
    }

    fun updatePhotoDuration(value: String) {
        _uiState.update {
            it.copy(
                photoDurationMinutes = value.filter(Char::isDigit).take(2),
                message = null,
            )
        }
    }

    fun updateSetupHost(value: String) {
        _uiState.update { it.copy(setupFormState = it.setupFormState.copy(host = value), message = null) }
    }

    fun updateSetupPort(value: String) {
        _uiState.update { it.copy(setupFormState = it.setupFormState.copy(port = value.filter(Char::isDigit)), message = null) }
    }

    fun updateSetupSsid(value: String) {
        _uiState.update { it.copy(setupFormState = it.setupFormState.copy(ssid = value), message = null) }
    }

    fun updateSetupPassword(value: String) {
        _uiState.update { it.copy(setupFormState = it.setupFormState.copy(password = value), message = null) }
    }

    fun updateSetupToken(value: String) {
        _uiState.update { it.copy(setupFormState = it.setupFormState.copy(token = value), message = null) }
    }

    fun connect() {
        val formState = _uiState.value.formState
        val endpoint = parseEndpointInput(formState.host, formState.port)
        val token = formState.token.trim()

        if (endpoint == null || token.isBlank()) {
            _uiState.update {
                it.copy(message = "Введи адрес и токен или отсканируй QR с зеркала.")
            }
            return
        }

        connectWithConfig(
            ServerConfig(
                host = endpoint.host,
                port = endpoint.port,
                token = token,
            ),
            persistConfig = true,
        )
    }

    fun connectToDiscovered(mirror: DiscoveredMirror) {
        val token = tokenStore.readToken() ?: _uiState.value.formState.token.trim()

        if (token.isBlank()) {
            _uiState.update {
                it.copy(
                    message = "Зеркало найдено. Теперь отсканируй QR на экране зеркала, чтобы получить токен.",
                    qrScannerOpen = true,
                )
            }
            return
        }

        connectWithConfig(
            ServerConfig(
                host = mirror.host,
                port = mirror.port,
                token = token,
            ),
            persistConfig = true,
        )
    }

    fun handleQrValue(rawValue: String) {
        val payload = parsePairingPayload(rawValue)

        if (payload == null) {
            _uiState.update { it.copy(message = "Это не QR-код зеркала.") }
            return
        }

        val discoveredMirror = _uiState.value.discoveredMirrors.firstOrNull { mirror ->
            payload.service == null || mirror.name == payload.service
        } ?: _uiState.value.discoveredMirrors.firstOrNull()
        val host = discoveredMirror?.host ?: payload.hosts.firstOrNull()

        if (host.isNullOrBlank()) {
            _uiState.update {
                it.copy(
                    qrScannerOpen = false,
                    manualExpanded = true,
                    formState = it.formState.copy(port = payload.port.toString()),
                    message = "QR считан, но в нём не было адреса. Введи адрес вручную и отсканируй QR заново.",
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    qrScannerOpen = false,
                    message = "QR считан. Подключаюсь к зеркалу...",
                )
            }

            try {
                val token = payload.token
                tokenStore.saveToken(token)
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        qrScannerOpen = false,
                        formState = it.formState.copy(host = host, port = payload.port.toString(), token = token),
                        message = "QR считан. Подключаюсь к $host:${payload.port}...",
                    )
                }

                connectWithConfig(
                    ServerConfig(host = host, port = payload.port, token = token),
                    persistConfig = true,
                )
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        message = error.message ?: "QR не подтвердился. Обнови QR на зеркале и попробуй снова.",
                    )
                }
            }
        }
    }

    fun submitSetup() {
        val setup = _uiState.value.setupFormState
        val endpoint = parseEndpointInput(setup.host, setup.port)
        val token = setup.token.trim()

        if (endpoint == null || setup.ssid.isBlank() || token.isBlank()) {
            _uiState.update {
                it.copy(message = "Для режима настройки нужны адрес сервера, имя Wi-Fi и токен.")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, message = "Передаём настройки Raspberry/Linux...") }

            try {
                withContext(Dispatchers.IO) {
                    api.sendSetupToken(endpoint.host, endpoint.port, token)
                    api.sendSetupWifi(endpoint.host, endpoint.port, setup.ssid.trim(), setup.password)
                    tokenStore.saveToken(token)
                }

                _uiState.update {
                    it.copy(
                        isBusy = false,
                        setupExpanded = false,
                        formState = it.formState.copy(port = endpoint.port.toString(), token = token),
                        message = "Настройки отправлены. Переключись в обычную Wi‑Fi сеть, затем нажми авто-поиск.",
                    )
                }
                startDiscovery()
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        message = error.message ?: "Не удалось передать настройки.",
                    )
                }
            }
        }
    }

    fun refreshState() {
        runServerAction("Состояние обновлено.") { config ->
            api.fetchState(config)
        }
    }

    fun sendDisplayAction(action: String) {
        runServerAction(
            successMessage = when (action) {
                "on", "off" -> null
                else -> "Перезагрузка отправлена."
            },
        ) { config ->
            api.sendDisplayAction(config, action)
        }
    }

    fun setDisplayMode(mode: String) {
        runServerAction(
            successMessage = when (mode) {
                "gallery", "ar" -> null
                else -> null
            },
        ) { config ->
            api.setDisplayMode(config, mode)
        }
    }

    fun setModuleVisibility(module: MirrorModule, visible: Boolean) {
        runServerAction(
            successMessage = null,
        ) { config ->
            api.setModuleVisibility(config, module.id, visible)
        }
    }

    fun refreshAllModules() {
        runServerAction("Все обновляемые модули обновлены.") { config ->
            api.refreshAll(config)
        }
    }

    fun openLayoutEditor() {
        val config = currentConfig
        val state = _uiState.value.mirrorState
        if (config == null || state == null) {
            _uiState.update { it.copy(message = "Сначала подключись к серверу.") }
            return
        }

        val draft = state.modules.associateBy({ it.id }, { it.layout })
        _uiState.update {
            it.copy(
                layoutEditorOpen = true,
                layoutDraft = draft,
                layoutSnapshot = draft,
                message = null,
            )
        }

        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.setLayoutEditMode(config, active = true)
                }
            }.onSuccess { updatedState ->
                _uiState.update {
                    it.copy(mirrorState = updatedState)
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        layoutEditorOpen = false,
                        layoutDraft = emptyMap(),
                        layoutSnapshot = emptyMap(),
                        message = error.message ?: "Не удалось открыть редактор.",
                    )
                }
            }
        }
    }

    fun moveLayoutModule(moduleId: String, layout: MirrorModuleLayout) {
        _uiState.update {
            it.copy(
                layoutDraft = it.layoutDraft + (moduleId to clampLayout(layout)),
                message = null,
            )
        }
    }

    fun cancelLayoutEditor() {
        closeLayoutEditor()
    }

    fun saveLayoutEditor() {
        val config = currentConfig
        if (config == null) {
            _uiState.update { it.copy(message = "Сначала подключись к серверу.") }
            return
        }

        val updates = _uiState.value.layoutDraft.map { (id, layout) ->
            MirrorModuleLayoutUpdate(id = id, x = layout.x, y = layout.y, w = layout.w, h = layout.h)
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, message = "Сохраняем раскладку...") }

            try {
                val updatedState = withContext(Dispatchers.IO) {
                    api.updateModuleLayout(config, updates)
                    api.setLayoutEditMode(config, active = false)
                }

                _uiState.update {
                    it.copy(
                        isBusy = false,
                        layoutEditorOpen = false,
                        layoutDraft = emptyMap(),
                        layoutSnapshot = emptyMap(),
                        mirrorState = updatedState,
                        message = "Раскладка сохранена.",
                    )
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        message = error.message ?: "Не удалось сохранить раскладку.",
                    )
                }
            }
        }
    }

    fun resetLayoutEditor() {
        val config = currentConfig
        if (config == null) {
            _uiState.update { it.copy(message = "Сначала подключись к серверу.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, message = "Сбрасываем раскладку...") }

            try {
                val updatedState = withContext(Dispatchers.IO) {
                    api.resetModuleLayout(config)
                }

                _uiState.update {
                    it.copy(
                        isBusy = false,
                        mirrorState = updatedState,
                        layoutDraft = updatedState.modules.associateBy({ it.id }, { it.layout }),
                        message = "Раскладка сброшена.",
                    )
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        message = error.message ?: "Не удалось сбросить раскладку.",
                    )
                }
            }
        }
    }

    fun uploadPhoto(context: Context, uri: Uri) {
        val config = currentConfig
        if (config == null) {
            _uiState.update { it.copy(message = "Сначала подключись к серверу.") }
            return
        }

        val durationMinutes = _uiState.value.photoDurationMinutes.toIntOrNull()?.coerceIn(1, 60)
        if (durationMinutes == null) {
            _uiState.update { it.copy(message = "Укажи время показа от 1 до 60 минут.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, message = "Готовим фото...") }

            try {
                val updatedState = withContext(Dispatchers.IO) {
                    val photo = preparePhotoUpload(context.applicationContext, uri)
                    api.uploadPhoto(
                        config = config,
                        imageBytes = photo.bytes,
                        mimeType = photo.mimeType,
                        durationSeconds = durationMinutes * 60,
                    )
                }

                _uiState.update {
                    it.copy(
                        isBusy = false,
                        isConnected = true,
                        mirrorState = updatedState,
                        message = "Фото отправлено на $durationMinutes мин.",
                    )
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        message = error.message ?: "Не удалось отправить фото.",
                    )
                }
            }
        }
    }

    fun clearPhoto() {
        runServerAction("Фото убрано с зеркала.") { config ->
            api.clearPhoto(config)
        }
    }

    fun disconnect() {
        currentConfig = null
        _uiState.update {
            it.copy(
                isConnected = false,
                mirrorState = null,
                diagnostics = null,
                endpointLabel = "",
                layoutEditorOpen = false,
                layoutDraft = emptyMap(),
                layoutSnapshot = emptyMap(),
                message = "Можно выбрать другое зеркало или снова сканировать QR.",
            )
        }
        startDiscovery()
    }

    override fun onCleared() {
        discovery.close()
        super.onCleared()
    }

    private fun closeLayoutEditor() {
        val message = "Редактирование отменено."
        val config = currentConfig
        val snapshot = _uiState.value.layoutSnapshot

        _uiState.update {
            it.copy(
                layoutEditorOpen = false,
                layoutDraft = emptyMap(),
                layoutSnapshot = emptyMap(),
                message = message,
            )
        }

        if (config == null) {
            return
        }

        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (snapshot.isNotEmpty()) {
                        api.updateModuleLayout(
                            config = config,
                            modules = snapshot.map { (id, layout) ->
                                MirrorModuleLayoutUpdate(id = id, x = layout.x, y = layout.y, w = layout.w, h = layout.h)
                            },
                        )
                    }
                    api.setLayoutEditMode(config, active = false)
                }
            }.onSuccess { updatedState ->
                _uiState.update {
                    it.copy(mirrorState = updatedState)
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(message = error.message ?: "Не удалось восстановить раскладку.")
                }
            }
        }
    }

    private fun connectWithConfig(config: ServerConfig, persistConfig: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, message = "Проверяем зеркало...", isConnected = false) }

            try {
                val state = withContext(Dispatchers.IO) {
                    api.checkHealth(config)
                    val fetchedState = api.fetchState(config)
                    api.markPairingComplete(config)
                    val diagnostics = runCatching { api.fetchDiagnostics(config) }.getOrNull()
                    fetchedState to diagnostics
                }

                if (persistConfig) {
                    repository.saveEndpoint(config)
                    tokenStore.saveToken(config.token)
                }

                currentConfig = config
                discovery.stop()
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        isScanning = false,
                        isConnected = true,
                        endpointLabel = "Подключено к ${config.host}:${config.port}",
                        mirrorState = state.first,
                        diagnostics = state.second,
                        message = "Подключение готово.",
                    )
                }
            } catch (error: Exception) {
                currentConfig = null
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        isConnected = false,
                        mirrorState = null,
                        diagnostics = null,
                        endpointLabel = "",
                        message = error.message ?: "Не удалось подключиться к серверу.",
                    )
                }
            }
        }
    }

    private fun runServerAction(
        successMessage: String?,
        action: (ServerConfig) -> MirrorState,
    ) {
        val config = currentConfig
        if (config == null) {
            _uiState.update { it.copy(message = "Сначала подключись к серверу.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, message = null) }

            try {
                val updatedState = withContext(Dispatchers.IO) {
                    action(config)
                }

                _uiState.update {
                    it.copy(
                        isBusy = false,
                        isConnected = true,
                        mirrorState = updatedState,
                        message = successMessage,
                    )
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        message = error.message ?: "Запрос не выполнен.",
                    )
                }
            }
        }
    }

    companion object {
        fun factory(
            api: MagicMirrorApi,
            repository: ServerConfigRepository,
            discovery: MagicMirrorDiscovery,
            tokenStore: SecureTokenStore,
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MainViewModel(
                        api = api,
                        repository = repository,
                        discovery = discovery,
                        tokenStore = tokenStore,
                    ) as T
                }
            }
        }
    }
}

private fun SavedServerEndpoint.toFormState(
    currentState: ConnectionFormState,
    token: String,
): ConnectionFormState {
    return currentState.copy(
        host = host,
        port = port.toString(),
        token = token,
    )
}

private fun preparePhotoUpload(context: Context, uri: Uri): PreparedPhoto {
    val resolver = context.contentResolver
    val mimeType = resolver.getType(uri)?.lowercase().orEmpty()
    val rawBytes = resolver.openInputStream(uri)?.use { input ->
        input.readBytes()
    } ?: throw IllegalArgumentException("Не удалось прочитать фото.")

    if (mimeType in setOf("image/jpeg", "image/png", "image/webp") && rawBytes.size <= MaxPhotoUploadBytes) {
        return PreparedPhoto(rawBytes, mimeType)
    }

    val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
        ?: throw IllegalArgumentException("Выбери JPEG, PNG или WebP фото.")
    val scaled = scaleBitmapIfNeeded(bitmap)
    val compressed = compressJpegUnderLimit(scaled)

    if (scaled !== bitmap) {
        scaled.recycle()
    }
    bitmap.recycle()

    return PreparedPhoto(compressed, "image/jpeg")
}

private fun scaleBitmapIfNeeded(bitmap: Bitmap): Bitmap {
    val longestSide = max(bitmap.width, bitmap.height)
    if (longestSide <= MaxPhotoDimension) {
        return bitmap
    }

    val scaleFactor = MaxPhotoDimension.toFloat() / longestSide.toFloat()
    val width = (bitmap.width * scaleFactor).roundToInt().coerceAtLeast(1)
    val height = (bitmap.height * scaleFactor).roundToInt().coerceAtLeast(1)
    return bitmap.scale(width, height, filter = true)
}

private fun compressJpegUnderLimit(bitmap: Bitmap): ByteArray {
    val output = ByteArrayOutputStream()
    var quality = 88
    var bytes: ByteArray

    do {
        output.reset()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
        bytes = output.toByteArray()
        quality -= 8
    } while (bytes.size > MaxPhotoUploadBytes && quality >= 56)

    if (bytes.size > MaxPhotoUploadBytes) {
        throw IllegalArgumentException("Фото слишком большое. Выбери снимок до 6 МБ или обрежь его.")
    }

    return bytes
}
