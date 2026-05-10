package com.codex.magicmirrorcontroller.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.codex.magicmirrorcontroller.data.ConnectionFormState
import com.codex.magicmirrorcontroller.data.DiscoveredMirror
import com.codex.magicmirrorcontroller.data.MagicMirrorApi
import com.codex.magicmirrorcontroller.data.MagicMirrorDiscovery
import com.codex.magicmirrorcontroller.data.MirrorModule
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
    val endpointLabel: String = "",
    val isConnected: Boolean = false,
    val isBusy: Boolean = false,
    val isScanning: Boolean = false,
    val initialized: Boolean = false,
    val manualExpanded: Boolean = false,
    val setupExpanded: Boolean = false,
    val qrScannerOpen: Boolean = false,
    val message: String? = null,
)

private val moduleTitleRu = mapOf(
    "clock" to "Часы",
    "weather" to "Погода",
    "markets" to "Курсы",
    "calendar" to "События",
    "news" to "Новости",
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
                    formState = it.formState.copy(token = payload.token, port = payload.port.toString()),
                    message = "Токен сохранён, но в QR не было адреса. Введи адрес вручную.",
                )
            }
            tokenStore.saveToken(payload.token)
            return
        }

        tokenStore.saveToken(payload.token)
        _uiState.update {
            it.copy(
                qrScannerOpen = false,
                formState = it.formState.copy(host = host, port = payload.port.toString(), token = payload.token),
                message = "QR считан. Подключаюсь к $host:${payload.port}...",
            )
        }

        connectWithConfig(
            ServerConfig(host = host, port = payload.port, token = payload.token),
            persistConfig = true,
        )
    }

    fun submitSetup() {
        val setup = _uiState.value.setupFormState
        val endpoint = parseEndpointInput(setup.host, setup.port)
        val token = setup.token.trim()

        if (endpoint == null || setup.ssid.isBlank() || token.length < 8) {
            _uiState.update {
                it.copy(message = "Для режима настройки нужны адрес сервера, имя Wi-Fi и токен минимум 8 символов.")
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
                "on" -> "Экран включён."
                "off" -> "Экран выключен."
                else -> "Перезагрузка отправлена."
            },
        ) { config ->
            api.sendDisplayAction(config, action)
        }
    }

    fun setDisplayMode(mode: String) {
        runServerAction(
            successMessage = when (mode) {
                "gallery" -> "Экран ожидания с картинами включён."
                "ar" -> "Примерка включена."
                else -> "Виджеты зеркала включены."
            },
        ) { config ->
            api.setDisplayMode(config, mode)
        }
    }

    fun setModuleVisibility(module: MirrorModule, visible: Boolean) {
        val title = moduleTitleRu[module.id] ?: module.title

        runServerAction(
            successMessage = if (visible) {
                "$title включён."
            } else {
                "$title выключен."
            },
        ) { config ->
            api.setModuleVisibility(config, module.id, visible)
        }
    }

    fun refreshModule(moduleId: String) {
        runServerAction("Модуль обновлён.") { config ->
            api.refreshModule(config, moduleId)
        }
    }

    fun refreshAllModules() {
        runServerAction("Все обновляемые модули обновлены.") { config ->
            api.refreshAll(config)
        }
    }

    fun disconnect() {
        currentConfig = null
        _uiState.update {
            it.copy(
                isConnected = false,
                mirrorState = null,
                endpointLabel = "",
                message = "Можно выбрать другое зеркало или снова сканировать QR.",
            )
        }
        startDiscovery()
    }

    override fun onCleared() {
        discovery.close()
        super.onCleared()
    }

    private fun connectWithConfig(config: ServerConfig, persistConfig: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, message = "Проверяем зеркало...", isConnected = false) }

            try {
                val state = withContext(Dispatchers.IO) {
                    api.checkHealth(config)
                    val fetchedState = api.fetchState(config)
                    api.markPairingComplete(config)
                    fetchedState
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
                        mirrorState = state,
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
                        endpointLabel = "",
                        message = error.message ?: "Не удалось подключиться к серверу.",
                    )
                }
            }
        }
    }

    private fun runServerAction(
        successMessage: String,
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
