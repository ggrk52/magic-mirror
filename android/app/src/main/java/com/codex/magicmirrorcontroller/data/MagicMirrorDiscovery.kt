package com.codex.magicmirrorcontroller.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

class MagicMirrorDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(NsdManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var subnetScanJob: Job? = null
    private val queuedOrResolvingServices = mutableSetOf<String>()
    private val pendingServices = ArrayDeque<NsdServiceInfo>()
    private var isResolving = false
    private var onMirrorResolved: ((DiscoveredMirror) -> Unit)? = null

    fun start(
        onMirrorFound: (DiscoveredMirror) -> Unit,
        onStatus: (String, Boolean) -> Unit,
    ) {
        stop()
        acquireMulticastLock()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                onStatus("Ищем зеркало через mDNS и быстрый скан сети...", true)
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceTypeMatches(serviceInfo.serviceType)) {
                    return
                }

                queueResolve(serviceInfo, onMirrorFound)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                onStatus("Зеркало пропало из mDNS, продолжаем поиск...", true)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                onStatus("Поиск остановлен.", false)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                onStatus("Не удалось запустить авто-поиск: $errorCode", false)
                stop()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                onStatus("Не удалось остановить авто-поиск: $errorCode", false)
                stop()
            }
        }

        discoveryListener = listener
        runCatching {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { error ->
            onStatus("mDNS не стартовал: ${error.message ?: "ошибка"}. Пробую быстрый скан сети.", true)
        }
        startSubnetScan(onMirrorFound, onStatus)
    }

    fun stop() {
        val listener = discoveryListener
        if (listener != null) {
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
        discoveryListener = null
        subnetScanJob?.cancel()
        subnetScanJob = null
        releaseMulticastLock()
        queuedOrResolvingServices.clear()
        pendingServices.clear()
        isResolving = false
        onMirrorResolved = null
    }

    fun close() {
        stop()
        scope.cancel()
    }

    private fun acquireMulticastLock() {
        runCatching {
            multicastLock = wifiManager.createMulticastLock("magic-mirror-mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { lock ->
            runCatching {
                if (lock.isHeld) {
                    lock.release()
                }
            }
        }
        multicastLock = null
    }

    private fun startSubnetScan(
        onMirrorFound: (DiscoveredMirror) -> Unit,
        onStatus: (String, Boolean) -> Unit,
    ) {
        subnetScanJob?.cancel()
        subnetScanJob = scope.launch {
            delay(900)

            val prefix = localIpv4Prefix()
            if (prefix == null) {
                onStatus("mDNS работает. Быстрый скан подсети недоступен для этой сети.", true)
                return@launch
            }

            val semaphore = Semaphore(32)
            val foundCount = AtomicInteger(0)
            val suffixes = (listOf(1, 2, 75, 100, 101, 50, 10, 20, 254) + (1..254)).distinct()

            suffixes.map { suffix ->
                async {
                    semaphore.withPermit {
                        if (!isActive) {
                            return@withPermit
                        }

                        probeMirror("$prefix.$suffix")?.let { mirror ->
                            foundCount.incrementAndGet()
                            onMirrorFound(mirror)
                        }
                    }
                }
            }.awaitAll()

            if (isActive && foundCount.get() == 0) {
                onStatus("Авто-поиск пока ничего не нашёл. QR всё ещё самый быстрый путь.", true)
            }
        }
    }

    private fun localIpv4Prefix(): String? {
        val dhcpInfo = runCatching { wifiManager.dhcpInfo }.getOrNull() ?: return null
        val address = dhcpInfo.ipAddress.takeIf { it != 0 } ?: dhcpInfo.gateway.takeIf { it != 0 } ?: return null
        val ip = intToIpv4(address)
        return ip.substringBeforeLast('.', missingDelimiterValue = "").takeIf { it.isNotBlank() }
    }

    private fun intToIpv4(value: Int): String {
        return listOf(
            value and 0xff,
            value shr 8 and 0xff,
            value shr 16 and 0xff,
            value shr 24 and 0xff,
        ).joinToString(".")
    }

    private fun probeMirror(host: String): DiscoveredMirror? {
        return try {
            val connection = (URL("http://$host:8080/api/health").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 320
                readTimeout = 320
                setRequestProperty("Accept", "application/json")
            }

            try {
                if (connection.responseCode !in 200..299) {
                    return null
                }

                val payload = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(payload)

                if (json.optString("status") != "ok" || json.optString("service") != "_magicmirror._tcp") {
                    return null
                }

                DiscoveredMirror(
                    name = json.optString("name", "Magic Mirror"),
                    host = host,
                    port = 8080,
                    instanceId = null,
                    version = json.optString("version").takeIf { it.isNotBlank() },
                    setupMode = json.optBoolean("setupMode", false),
                )
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun queueResolve(
        serviceInfo: NsdServiceInfo,
        onMirrorFound: (DiscoveredMirror) -> Unit,
    ) {
        val key = "${serviceInfo.serviceName}:${serviceInfo.serviceType}"
        if (!queuedOrResolvingServices.add(key)) {
            return
        }

        onMirrorResolved = onMirrorFound
        pendingServices.addLast(serviceInfo)
        resolveNext()
    }

    private fun resolveNext() {
        if (isResolving) {
            return
        }

        val serviceInfo = pendingServices.removeFirstOrNull() ?: return
        val key = "${serviceInfo.serviceName}:${serviceInfo.serviceType}"
        isResolving = true

        @Suppress("DEPRECATION")
        nsdManager.resolveService(
            serviceInfo,
            object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolvedService: NsdServiceInfo) {
                    queuedOrResolvingServices.remove(key)
                    isResolving = false

                    val host = resolvedService.host?.hostAddress
                    if (host != null) {
                        val txt = resolvedService.txtAttributes()
                        onMirrorResolved?.invoke(
                            DiscoveredMirror(
                                name = resolvedService.serviceName.ifBlank { "Magic Mirror" },
                                host = host,
                                port = resolvedService.port,
                                instanceId = txt["instance"],
                                version = txt["version"],
                                setupMode = txt["setup"] == "1",
                            ),
                        )
                    }
                    resolveNext()
                }

                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    queuedOrResolvingServices.remove(key)
                    isResolving = false
                    resolveNext()
                }
            },
        )
    }

    private fun NsdServiceInfo.txtAttributes(): Map<String, String> {
        return attributes.mapValues { (_, value) -> value.decodeToString() }
    }

    private fun serviceTypeMatches(value: String): Boolean {
        return value.trimEnd('.').equals(SERVICE_TYPE.trimEnd('.'), ignoreCase = true)
    }

    private companion object {
        const val SERVICE_TYPE = "_magicmirror._tcp."
    }
}
