package com.codex.magicmirrorcontroller

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codex.magicmirrorcontroller.data.ConnectionFormState
import com.codex.magicmirrorcontroller.data.DiscoveredMirror
import com.codex.magicmirrorcontroller.data.MirrorModule
import com.codex.magicmirrorcontroller.data.MirrorState
import com.codex.magicmirrorcontroller.data.SetupFormState
import com.codex.magicmirrorcontroller.ui.MainUiState
import com.codex.magicmirrorcontroller.ui.MainViewModel
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

private val mirrorColorScheme = darkColorScheme(
    primary = Color(0xFFBDEBFF),
    secondary = Color(0xFFA7FFD7),
    tertiary = Color(0xFFFFD59A),
    background = Color(0xFF020408),
    surface = Color(0xFF101723),
    surfaceVariant = Color(0xFF182434),
    onSurface = Color(0xFFF6FBFF),
    onSurfaceVariant = Color(0xFFB5C2CF),
)

private val glassShape = RoundedCornerShape(30.dp)
private val glassBorder = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))

@Composable
fun MagicMirrorApp(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    MaterialTheme(colorScheme = mirrorColorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                MirrorBackground()

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets.safeDrawing,
                ) { padding ->
                    if (state.isConnected && state.mirrorState != null) {
                        ControlScreen(
                            padding = padding,
                            state = state,
                            onRefresh = viewModel::refreshState,
                            onDisconnect = viewModel::disconnect,
                            onDisplayAction = viewModel::sendDisplayAction,
                            onDisplayModeChange = viewModel::setDisplayMode,
                            onModuleVisibilityChange = viewModel::setModuleVisibility,
                            onRefreshAll = viewModel::refreshAllModules,
                        )
                    } else {
                        ConnectionScreen(
                            padding = padding,
                            state = state,
                            onStartDiscovery = viewModel::startDiscovery,
                            onStopDiscovery = viewModel::stopDiscovery,
                            onMirrorClick = viewModel::connectToDiscovered,
                            onOpenQr = viewModel::openQrScanner,
                            onToggleManual = viewModel::toggleManual,
                            onToggleSetup = viewModel::toggleSetup,
                            onHostChange = viewModel::updateHost,
                            onPortChange = viewModel::updatePort,
                            onTokenChange = viewModel::updateToken,
                            onConnect = viewModel::connect,
                            onSetupHostChange = viewModel::updateSetupHost,
                            onSetupPortChange = viewModel::updateSetupPort,
                            onSetupSsidChange = viewModel::updateSetupSsid,
                            onSetupPasswordChange = viewModel::updateSetupPassword,
                            onSetupTokenChange = viewModel::updateSetupToken,
                            onSubmitSetup = viewModel::submitSetup,
                        )
                    }
                }

                if (state.qrScannerOpen) {
                    QrScannerOverlay(
                        onQrFound = viewModel::handleQrValue,
                        onClose = viewModel::closeQrScanner,
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.initialize()
    }
}

@Composable
private fun MirrorBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF0C1521),
                        Color(0xFF03060B),
                        Color(0xFF000000),
                    ),
                ),
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF26445E).copy(alpha = 0.55f), Color.Transparent),
                        center = Offset(140f, 120f),
                        radius = 680f,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF5A3B1B).copy(alpha = 0.2f), Color.Transparent),
                        center = Offset(900f, 1400f),
                        radius = 920f,
                    ),
                ),
        )
    }
}

@Composable
private fun ConnectionScreen(
    padding: PaddingValues,
    state: MainUiState,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onMirrorClick: (DiscoveredMirror) -> Unit,
    onOpenQr: () -> Unit,
    onToggleManual: () -> Unit,
    onToggleSetup: () -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onConnect: () -> Unit,
    onSetupHostChange: (String) -> Unit,
    onSetupPortChange: (String) -> Unit,
    onSetupSsidChange: (String) -> Unit,
    onSetupPasswordChange: (String) -> Unit,
    onSetupTokenChange: (String) -> Unit,
    onSubmitSetup: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            HeaderBlock(
                kicker = "MAGIC MIRROR",
                title = "Коннектор",
                subtitle = "Поиск сервера и подключение по QR.",
            )
        }

        item {
            GlassPanel {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    RadarOrb(active = state.isScanning)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (state.isScanning) "Поиск" else "Остановлено",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Найдено: ${state.discoveredMirrors.size}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onStartDiscovery,
                        enabled = !state.isBusy,
                    ) {
                        Text("Искать")
                    }
                    OutlinedButton(
                        onClick = onStopDiscovery,
                        enabled = state.isScanning && !state.isBusy,
                    ) {
                        Text("Стоп")
                    }
                    OutlinedButton(onClick = onOpenQr, enabled = !state.isBusy) {
                        Text("QR")
                    }
                }
            }
        }

        if (state.discoveredMirrors.isNotEmpty()) {
            items(state.discoveredMirrors, key = { "${it.host}:${it.port}" }) { mirror ->
                MirrorCard(
                    mirror = mirror,
                    isBusy = state.isBusy,
                    onClick = { onMirrorClick(mirror) },
                )
            }
        } else {
            item {
                EmptyDiscoveryCard(onOpenQr = onOpenQr)
            }
        }

        item {
            ActionFoldout(
                title = "Ручной ввод",
                subtitle = "Host, порт, токен",
                expanded = state.manualExpanded,
                onToggle = onToggleManual,
            ) {
                ManualForm(
                    formState = state.formState,
                    isBusy = state.isBusy,
                    onHostChange = onHostChange,
                    onPortChange = onPortChange,
                    onTokenChange = onTokenChange,
                    onConnect = onConnect,
                )
            }
        }

        item {
            ActionFoldout(
                title = "Setup",
                subtitle = "Wi‑Fi настройка Raspberry/Linux",
                expanded = state.setupExpanded,
                onToggle = onToggleSetup,
            ) {
                SetupForm(
                    formState = state.setupFormState,
                    isBusy = state.isBusy,
                    onHostChange = onSetupHostChange,
                    onPortChange = onSetupPortChange,
                    onSsidChange = onSetupSsidChange,
                    onPasswordChange = onSetupPasswordChange,
                    onTokenChange = onSetupTokenChange,
                    onSubmit = onSubmitSetup,
                )
            }
        }

        item {
            MessageLine(message = state.message, busy = state.isBusy)
        }
    }
}

@Composable
private fun HeaderBlock(kicker: String, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = kicker,
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Light,
        )
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun GlassPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = glassShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
        border = glassBorder,
        shadowElevation = 18.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun RadarOrb(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "radar")
    val pulse by transition.animateFloat(
        initialValue = 0.18f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1700),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse",
    )
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(2400), repeatMode = RepeatMode.Restart),
        label = "sweep",
    )

    Canvas(
        modifier = Modifier
            .size(86.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.035f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape),
    ) {
        val radius = size.minDimension / 2f
        val centerPoint = center
        val sweepRadians = Math.toRadians(sweep.toDouble())
        val sweepEnd = Offset(
            x = centerPoint.x + kotlin.math.cos(sweepRadians).toFloat() * radius * 0.82f,
            y = centerPoint.y + kotlin.math.sin(sweepRadians).toFloat() * radius * 0.82f,
        )

        drawCircle(
            color = Color(0xFFBDEBFF).copy(alpha = if (active) 0.18f else 0.08f),
            radius = radius * pulse,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.12f),
            radius = radius * 0.64f,
            style = Stroke(width = 1.dp.toPx()),
        )
        drawLine(
            color = Color(0xFFA7FFD7).copy(alpha = if (active) 0.72f else 0.26f),
            start = centerPoint,
            end = sweepEnd,
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = Color(0xFFA7FFD7).copy(alpha = if (active) 0.92f else 0.42f),
            radius = 6.dp.toPx(),
            center = centerPoint,
        )
    }
}

@Composable
private fun MirrorCard(
    mirror: DiscoveredMirror,
    isBusy: Boolean,
    onClick: () -> Unit,
) {
    GlassPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mirror.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${mirror.host}:${mirror.port}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(onClick = onClick, enabled = !isBusy) {
                Text(if (mirror.setupMode) "Setup" else "Подключить")
            }
        }
    }
}

@Composable
private fun EmptyDiscoveryCard(onOpenQr: () -> Unit) {
    GlassPanel {
        Text(
            text = "Сервер не найден",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedButton(onClick = onOpenQr) {
            Text("QR")
        }
    }
}

@Composable
private fun ActionFoldout(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassPanel {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(text = subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(text = if (expanded) "Свернуть" else "Открыть", color = MaterialTheme.colorScheme.primary)
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun ManualForm(
    formState: ConnectionFormState,
    isBusy: Boolean,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onConnect: () -> Unit,
) {
    OutlinedTextField(
        value = formState.host,
        onValueChange = onHostChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Host, IP или URL") },
        singleLine = true,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = formState.port,
            onValueChange = onPortChange,
            modifier = Modifier.weight(0.35f),
            label = { Text("Порт") },
            singleLine = true,
        )
        OutlinedTextField(
            value = formState.token,
            onValueChange = onTokenChange,
            modifier = Modifier.weight(0.65f),
            label = { Text("Токен") },
            singleLine = true,
        )
    }
    Button(onClick = onConnect, modifier = Modifier.fillMaxWidth(), enabled = !isBusy) {
        Text("Проверить подключение")
    }
}

@Composable
private fun SetupForm(
    formState: SetupFormState,
    isBusy: Boolean,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onSsidChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Text(
        text = "MIRROR_SETUP_MODE=1",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = formState.host,
            onValueChange = onHostChange,
            modifier = Modifier.weight(0.62f),
            label = { Text("Setup host") },
            singleLine = true,
        )
        OutlinedTextField(
            value = formState.port,
            onValueChange = onPortChange,
            modifier = Modifier.weight(0.38f),
            label = { Text("Порт") },
            singleLine = true,
        )
    }
    OutlinedTextField(
        value = formState.ssid,
        onValueChange = onSsidChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Wi‑Fi SSID") },
        singleLine = true,
    )
    OutlinedTextField(
        value = formState.password,
        onValueChange = onPasswordChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Wi‑Fi пароль") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
    )
    OutlinedTextField(
        value = formState.token,
        onValueChange = onTokenChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Токен зеркала") },
        singleLine = true,
    )
    Button(onClick = onSubmit, modifier = Modifier.fillMaxWidth(), enabled = !isBusy) {
        Text("Передать настройки")
    }
}

@Composable
private fun ControlScreen(
    padding: PaddingValues,
    state: MainUiState,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
    onDisplayAction: (String) -> Unit,
    onDisplayModeChange: (String) -> Unit,
    onModuleVisibilityChange: (MirrorModule, Boolean) -> Unit,
    onRefreshAll: () -> Unit,
) {
    val mirrorState = state.mirrorState ?: return

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            HeaderBlock(
                kicker = state.endpointLabel,
                title = "Пульт",
                subtitle = "Экран и модули.",
            )
        }

        item {
            StatusCard(
                mirrorState = mirrorState,
                isBusy = state.isBusy,
                onDisplayAction = onDisplayAction,
                onDisplayModeChange = onDisplayModeChange,
                onRefreshAll = onRefreshAll,
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onRefresh, enabled = !state.isBusy) {
                    Text("Обновить")
                }
                OutlinedButton(onClick = onDisconnect, enabled = !state.isBusy) {
                    Text("Сменить зеркало")
                }
            }
            MessageLine(message = state.message, busy = state.isBusy)
        }

        items(mirrorState.modules, key = { it.id }) { module ->
            ModuleCard(
                module = module,
                isBusy = state.isBusy,
                onToggle = { onModuleVisibilityChange(module, it) },
            )
        }
    }
}

@Composable
private fun StatusCard(
    mirrorState: MirrorState,
    isBusy: Boolean,
    onDisplayAction: (String) -> Unit,
    onDisplayModeChange: (String) -> Unit,
    onRefreshAll: () -> Unit,
) {
    GlassPanel {
        Text(
            text = when {
                mirrorState.displayState == "off" -> "Экран выключен"
                mirrorState.displayMode == "gallery" -> "Картины"
                mirrorState.displayMode == "ar" -> "AR-примерка активна"
                else -> "Зеркало активно"
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Light,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { onDisplayAction("on") }, enabled = !isBusy) {
                Text("Вкл")
            }
            Button(onClick = { onDisplayAction("off") }, enabled = !isBusy) {
                Text("Выкл")
            }
            OutlinedButton(onClick = { onDisplayAction("reload") }, enabled = !isBusy) {
                Text("Reload")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeButton(
                label = "Зеркало",
                active = mirrorState.displayMode == "mirror",
                enabled = !isBusy,
                onClick = { onDisplayModeChange("mirror") },
            )
            ModeButton(
                label = "Картины",
                active = mirrorState.displayMode == "gallery",
                enabled = !isBusy,
                onClick = { onDisplayModeChange("gallery") },
            )
            ModeButton(
                label = "AR",
                active = mirrorState.displayMode == "ar",
                enabled = !isBusy,
                onClick = { onDisplayModeChange("ar") },
            )
        }

        OutlinedButton(onClick = onRefreshAll, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
            Text("Обновить все модули")
        }
    }
}

@Composable
private fun ModeButton(label: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val container = if (active) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
    } else {
        Color.Transparent
    }

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = container,
        border = BorderStroke(1.dp, Color.White.copy(alpha = if (active) 0.36f else 0.14f)),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun ModuleCard(
    module: MirrorModule,
    isBusy: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = module.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedButton(
                onClick = { onToggle(!module.visible) },
                enabled = !isBusy,
            ) {
                Text(
                    if (module.visible) {
                        "Скрыть"
                    } else {
                        "Показать"
                    },
                )
            }
        }
    }
}

@Composable
private fun MessageLine(message: String?, busy: Boolean) {
    if (message == null && !busy) {
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        Text(
            text = message ?: "Работаем...",
            color = MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun QrScannerOverlay(
    onQrFound: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .padding(22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
            border = glassBorder,
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("QR-pairing", style = MaterialTheme.typography.headlineSmall)
                        Text("Сканируй код на экране зеркала", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onClose) {
                        Text("Закрыть")
                    }
                }

                if (hasPermission) {
                    QrCameraPreview(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp)
                            .clip(RoundedCornerShape(24.dp)),
                        onQrFound = onQrFound,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Нужен доступ к камере, иначе QR не считать.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("Разрешить камеру")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun QrCameraPreview(
    modifier: Modifier,
    onQrFound: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var locked by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PreviewView(viewContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE

                val providerFuture = ProcessCameraProvider.getInstance(viewContext)
                providerFuture.addListener(
                    {
                        val provider = providerFuture.get()
                        cameraProvider = provider

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(surfaceProvider)
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        analysis.setAnalyzer(executor) { imageProxy ->
                            val mediaImage = imageProxy.image
                            if (mediaImage == null || locked) {
                                imageProxy.close()
                                return@setAnalyzer
                            }

                            val image = InputImage.fromMediaImage(
                                mediaImage,
                                imageProxy.imageInfo.rotationDegrees,
                            )

                            scanner.process(image)
                                .addOnSuccessListener { barcodes ->
                                    val rawValue = barcodes.firstOrNull()?.rawValue
                                    if (!rawValue.isNullOrBlank() && !locked) {
                                        locked = true
                                        post { onQrFound(rawValue) }
                                    }
                                }
                                .addOnCompleteListener {
                                    imageProxy.close()
                                }
                        }

                        provider.unbindAll()
                        val camera = provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                        camera.cameraControl.setZoomRatio(1.25f)
                    },
                    ContextCompat.getMainExecutor(viewContext),
                )
            }
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            cameraProvider?.unbindAll()
            scanner.close()
            executor.shutdown()
        }
    }
}
