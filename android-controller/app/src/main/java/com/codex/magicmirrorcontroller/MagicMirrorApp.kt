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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codex.magicmirrorcontroller.data.ConnectionFormState
import com.codex.magicmirrorcontroller.data.DiscoveredMirror
import com.codex.magicmirrorcontroller.data.MirrorModule
import com.codex.magicmirrorcontroller.data.MirrorModuleLayout
import com.codex.magicmirrorcontroller.data.MirrorPhotoOverlay
import com.codex.magicmirrorcontroller.data.MirrorState
import com.codex.magicmirrorcontroller.data.SetupFormState
import com.codex.magicmirrorcontroller.ui.MainUiState
import com.codex.magicmirrorcontroller.ui.MainViewModel
import com.codex.magicmirrorcontroller.ui.moveLayoutByPixels
import com.codex.magicmirrorcontroller.ui.resizeLayoutByPixels
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlin.math.roundToInt
import java.util.concurrent.Executors

private val ForestBlack = Color(0xFF000000)
private val DeepGreen = Color(0xFF050505)
private val PineGreen = Color(0xFF111111)
private val GlassGreen = Color(0xFF080808)
private val DarkPurple = Color(0xFF141414)
private val VelvetPurple = Color(0xFFECECEC)
private val SoftGreenWhite = Color(0xFFF5F5F5)
private val MutedSage = Color(0xFF9A9A9A)
private val PassiveSage = Color(0xFF5F5F5F)
private val SkeletonGreen = Color(0xFF1C1C1C)

private val mirrorColorScheme = darkColorScheme(
    primary = SoftGreenWhite,
    onPrimary = ForestBlack,
    secondary = MutedSage,
    tertiary = Color(0xFFCFCFCF),
    background = ForestBlack,
    surface = GlassGreen,
    surfaceVariant = PineGreen,
    onSurface = SoftGreenWhite,
    onSurfaceVariant = MutedSage.copy(alpha = 0.86f),
    outline = SoftGreenWhite.copy(alpha = 0.2f),
)

private val glassShape = RoundedCornerShape(30.dp)
private val glassBorder = BorderStroke(1.dp, SoftGreenWhite.copy(alpha = 0.12f))
private val mirrorFontFamily = FontFamily.SansSerif
private val mirrorDisplayFamily = FontFamily.SansSerif
private val moduleTitleRu = mapOf(
    "clock" to "Часы",
    "weather" to "Погода",
    "markets" to "Курсы",
    "calendar" to "События",
    "news" to "Новости",
)

private val mirrorTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = mirrorDisplayFamily,
        fontSize = 38.sp,
        lineHeight = 42.sp,
        fontWeight = FontWeight.Light,
        letterSpacing = (-0.7f).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = mirrorDisplayFamily,
        fontSize = 26.sp,
        lineHeight = 31.sp,
        fontWeight = FontWeight.Light,
        letterSpacing = (-0.2f).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = mirrorFontFamily,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontFamily = mirrorFontFamily,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontFamily = mirrorFontFamily,
        fontSize = 16.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontFamily = mirrorFontFamily,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontFamily = mirrorFontFamily,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp,
    ),
)

@Composable
fun MagicMirrorApp(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val photoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            viewModel.uploadPhoto(context, uri)
        }
    }

    MaterialTheme(colorScheme = mirrorColorScheme, typography = mirrorTypography) {
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
                            onOpenLayoutEditor = viewModel::openLayoutEditor,
                            onModuleVisibilityChange = viewModel::setModuleVisibility,
                            onPhotoDurationChange = viewModel::updatePhotoDuration,
                            onPickPhoto = { photoPickerLauncher.launch("image/*") },
                            onClearPhoto = viewModel::clearPhoto,
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

                val editorMirrorState = state.mirrorState
                if (state.layoutEditorOpen && editorMirrorState != null) {
                    LayoutEditorOverlay(
                        state = state,
                        modules = editorMirrorState.modules,
                        onMove = viewModel::moveLayoutModule,
                        onSave = viewModel::saveLayoutEditor,
                        onCancel = viewModel::cancelLayoutEditor,
                        onReset = viewModel::resetLayoutEditor,
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
    val motion = rememberInfiniteTransition(label = "mirror-background")
    val drift by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 11000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "background-drift",
    )
    val breath by motion.animateFloat(
        initialValue = 0.68f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "background-breath",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        DeepGreen,
                        ForestBlack,
                        Color.Black,
                    ),
                ),
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(PineGreen.copy(alpha = 0.42f + breath * 0.28f), Color.Transparent),
                        center = Offset(110f + drift * 140f, 160f + breath * 56f),
                        radius = 760f,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(DarkPurple.copy(alpha = 0.18f + breath * 0.18f), Color.Transparent),
                        center = Offset(900f - drift * 180f, 1280f - breath * 72f),
                        radius = 980f,
                    ),
                ),
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawLine(
                color = SoftGreenWhite.copy(alpha = 0.04f),
                start = Offset(-size.width * 0.1f, size.height * 0.18f),
                end = Offset(size.width * 1.1f, size.height * 0.38f),
                strokeWidth = 1.dp.toPx(),
            )
            drawLine(
                color = VelvetPurple.copy(alpha = 0.04f + breath * 0.06f),
                start = Offset(size.width * 0.18f, -size.height * 0.08f),
                end = Offset(size.width * (0.82f + drift * 0.04f), size.height * 1.08f),
                strokeWidth = 1.dp.toPx(),
            )
            drawCircle(
                color = VelvetPurple.copy(alpha = 0.06f + breath * 0.07f),
                radius = size.minDimension * (0.68f + breath * 0.04f),
                center = Offset(size.width * (0.46f + drift * 0.08f), size.height * 1.04f),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
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
                kicker = if (state.isScanning) "ПОИСК" else "ЗЕРКАЛО",
                title = "Коннектор",
                action = {
                    ConnectionSettingsMenu(
                        manualExpanded = state.manualExpanded,
                        setupExpanded = state.setupExpanded,
                        isBusy = state.isBusy,
                        onOpenQr = onOpenQr,
                        onToggleManual = onToggleManual,
                        onToggleSetup = onToggleSetup,
                    )
                },
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
                            text = if (state.isScanning) "Ищем зеркало" else "Готово к поиску",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Поиск",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        MirrorSwitch(
                            checked = state.isScanning,
                            onCheckedChange = { checked ->
                                if (checked) onStartDiscovery() else onStopDiscovery()
                            },
                            enabled = !state.isBusy,
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenQr,
                        enabled = !state.isBusy,
                        colors = mirrorOutlinedButtonColors(),
                        border = mirrorOutlinedButtonBorder(enabled = !state.isBusy),
                    ) {
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

        if (state.manualExpanded) {
            item {
                SettingsPanel(title = "Ручной ввод") {
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
        }

        if (state.setupExpanded) {
            item {
                SettingsPanel(title = "Передача Wi‑Fi") {
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
        }

    }
}

@Composable
private fun SettingsPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    GlassPanel {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun ConnectionSettingsMenu(
    manualExpanded: Boolean,
    setupExpanded: Boolean,
    isBusy: Boolean,
    onOpenQr: () -> Unit,
    onToggleManual: () -> Unit,
    onToggleSetup: () -> Unit,
) {
    OverflowMenu(enabled = !isBusy) { close ->
        DropdownMenuItem(
            text = { Text("QR") },
            onClick = {
                close()
                onOpenQr()
            },
        )
        DropdownMenuItem(
            text = { Text(if (manualExpanded) "Скрыть ручной ввод" else "Ручной ввод") },
            onClick = {
                close()
                onToggleManual()
            },
        )
        DropdownMenuItem(
            text = { Text(if (setupExpanded) "Скрыть Wi‑Fi" else "Передача Wi‑Fi") },
            onClick = {
                close()
                onToggleSetup()
            },
        )
    }
}

private fun isDiscoveryNoiseMessage(message: String): Boolean {
    return message.startsWith("Нашёл") ||
        message.startsWith("Ищем") ||
        message.startsWith("mDNS") ||
        message.startsWith("Авто-поиск") ||
        message.startsWith("Запускаю авто-поиск")
}

@Composable
private fun OverflowMenu(
    enabled: Boolean,
    content: @Composable (close: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { expanded = true },
            enabled = enabled,
        ) {
            Text(
                text = "⋮",
                color = if (enabled) MaterialTheme.colorScheme.onSurface else PassiveSage,
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            content { expanded = false }
        }
    }
}

@Composable
private fun HeaderBlock(
    kicker: String,
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                text = kicker,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Light,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        if (action != null) {
            action()
        }
    }
}

@Composable
private fun GlassPanel(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(glassShape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF141414).copy(alpha = 0.78f),
                        Color(0xFF030303).copy(alpha = 0.9f),
                    ),
                ),
            )
            .border(glassBorder, glassShape),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun mirrorButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.onSurface,
    contentColor = ForestBlack,
    disabledContainerColor = SkeletonGreen,
    disabledContentColor = PassiveSage,
)

@Composable
private fun mirrorOutlinedButtonColors() = ButtonDefaults.outlinedButtonColors(
    contentColor = MaterialTheme.colorScheme.onSurface,
    disabledContentColor = PassiveSage,
)

@Composable
private fun mirrorOutlinedButtonBorder(enabled: Boolean) = BorderStroke(
    1.dp,
    if (enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f) else PassiveSage.copy(alpha = 0.18f),
)

@Composable
private fun mirrorSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = ForestBlack,
    checkedTrackColor = SoftGreenWhite,
    checkedBorderColor = SoftGreenWhite.copy(alpha = 0.38f),
    uncheckedThumbColor = MutedSage,
    uncheckedTrackColor = SkeletonGreen,
    uncheckedBorderColor = PassiveSage.copy(alpha = 0.42f),
    disabledCheckedThumbColor = PassiveSage,
    disabledCheckedTrackColor = SkeletonGreen,
    disabledUncheckedThumbColor = PassiveSage.copy(alpha = 0.62f),
    disabledUncheckedTrackColor = SkeletonGreen.copy(alpha = 0.62f),
)

@Composable
private fun MirrorSwitch(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val capsule by animateColorAsState(
        targetValue = when {
            checked && enabled -> SoftGreenWhite.copy(alpha = 0.11f)
            checked -> SoftGreenWhite.copy(alpha = 0.05f)
            else -> SkeletonGreen.copy(alpha = 0.2f)
        },
        animationSpec = tween(durationMillis = 340, easing = FastOutSlowInEasing),
        label = "switch-capsule",
    )
    val border by animateColorAsState(
        targetValue = when {
            checked && enabled -> SoftGreenWhite.copy(alpha = 0.22f)
            enabled -> PassiveSage.copy(alpha = 0.14f)
            else -> PassiveSage.copy(alpha = 0.08f)
        },
        animationSpec = tween(durationMillis = 340, easing = FastOutSlowInEasing),
        label = "switch-border",
    )
    val scale by animateFloatAsState(
        targetValue = if (checked) 1f else 0.965f,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "switch-scale",
    )

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(999.dp))
            .background(capsule)
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .padding(horizontal = 3.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = mirrorSwitchColors(),
        )
    }
}

@Composable
private fun RadarOrb(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "radar")
    val signal by animateFloatAsState(
        targetValue = if (active) 1f else 0.34f,
        animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
        label = "radar-signal",
    )
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
            .size(112.dp)
            .graphicsLayer {
                scaleX = 0.96f + signal * 0.04f
                scaleY = 0.96f + signal * 0.04f
            }
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(
                        VelvetPurple.copy(alpha = 0.05f + signal * 0.13f),
                        PineGreen.copy(alpha = 0.08f + signal * 0.1f),
                        Color.Transparent,
                    ),
                ),
            )
            .border(1.dp, VelvetPurple.copy(alpha = 0.14f + signal * 0.28f), CircleShape),
    ) {
        val radius = size.minDimension / 2f
        val centerPoint = center
        val sweepRadians = Math.toRadians(sweep.toDouble())
        val sweepEnd = Offset(
            x = centerPoint.x + kotlin.math.cos(sweepRadians).toFloat() * radius * 0.82f,
            y = centerPoint.y + kotlin.math.sin(sweepRadians).toFloat() * radius * 0.82f,
        )

        drawCircle(
            color = PineGreen.copy(alpha = 0.38f),
            radius = radius * 0.92f,
        )
        drawCircle(
            color = VelvetPurple.copy(alpha = 0.08f + signal * 0.22f),
            radius = radius * pulse,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
        drawCircle(
            color = SoftGreenWhite.copy(alpha = 0.12f),
            radius = radius * 0.64f,
            style = Stroke(width = 1.dp.toPx()),
        )
        drawCircle(
            color = VelvetPurple.copy(alpha = 0.14f + signal * 0.38f),
            radius = radius * 0.84f,
            style = Stroke(width = 1.dp.toPx()),
        )
        drawLine(
            color = SoftGreenWhite.copy(alpha = 0.08f),
            start = Offset(centerPoint.x - radius * 0.72f, centerPoint.y),
            end = Offset(centerPoint.x + radius * 0.72f, centerPoint.y),
            strokeWidth = 1.dp.toPx(),
        )
        drawLine(
            color = SoftGreenWhite.copy(alpha = 0.08f),
            start = Offset(centerPoint.x, centerPoint.y - radius * 0.72f),
            end = Offset(centerPoint.x, centerPoint.y + radius * 0.72f),
            strokeWidth = 1.dp.toPx(),
        )
        drawLine(
            color = VelvetPurple.copy(alpha = 0.28f + signal * 0.64f),
            start = centerPoint,
            end = sweepEnd,
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = VelvetPurple.copy(alpha = 0.36f + signal * 0.62f),
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
                    text = if (mirror.setupMode) "НАСТРОЙКА" else "В СЕТИ",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.ExtraBold,
                )
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
            Button(
                onClick = onClick,
                enabled = !isBusy,
                colors = mirrorButtonColors(),
            ) {
                Text(if (mirror.setupMode) "Настроить" else "Подключить")
            }
        }
    }
}

@Composable
private fun EmptyDiscoveryCard(onOpenQr: () -> Unit) {
    GlassPanel {
        Text(
            text = "Зеркало не найдено",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedButton(
            onClick = onOpenQr,
            colors = mirrorOutlinedButtonColors(),
            border = mirrorOutlinedButtonBorder(enabled = true),
        ) {
            Text("QR")
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
        label = { Text("Адрес, IP или URL") },
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
    Button(
        onClick = onConnect,
        modifier = Modifier.fillMaxWidth(),
        enabled = !isBusy,
        colors = mirrorButtonColors(),
    ) {
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
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = formState.host,
            onValueChange = onHostChange,
            modifier = Modifier.weight(0.62f),
            label = { Text("Адрес настройки") },
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
        label = { Text("Имя Wi‑Fi") },
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
    Button(
        onClick = onSubmit,
        modifier = Modifier.fillMaxWidth(),
        enabled = !isBusy,
        colors = mirrorButtonColors(),
    ) {
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
    onOpenLayoutEditor: () -> Unit,
    onModuleVisibilityChange: (MirrorModule, Boolean) -> Unit,
    onPhotoDurationChange: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onClearPhoto: () -> Unit,
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
                kicker = "ЗЕРКАЛО",
                title = "Пульт",
                action = {
                    ControlSettingsMenu(
                        isBusy = state.isBusy,
                        onRefresh = onRefresh,
                        onDisconnect = onDisconnect,
                        onReload = { onDisplayAction("reload") },
                        onRefreshAll = onRefreshAll,
                    )
                },
            )
        }

        item {
            StatusCard(
                mirrorState = mirrorState,
                isBusy = state.isBusy,
                onDisplayAction = onDisplayAction,
                onDisplayModeChange = onDisplayModeChange,
            )
        }

        item {
            // Hardware Status Card for Orange Pi
            if (state.diagnostics != null) {
                HardwareStatusCard(
                    diagnostics = state.diagnostics,
                    isBusy = state.isBusy,
                    onRefresh = onRefresh
                )
            }
        }

        item {
            LayoutCard(
                isBusy = state.isBusy,
                onOpen = onOpenLayoutEditor,
            )
        }


        item {
            ModuleGrid(
                modules = mirrorState.modules,
                isBusy = state.isBusy,
                onToggle = onModuleVisibilityChange,
            )
        }

        item {
            PhotoUploadCard(
                durationMinutes = state.photoDurationMinutes,
                photoOverlay = mirrorState.photoOverlay,
                isBusy = state.isBusy,
                onDurationChange = onPhotoDurationChange,
                onPickPhoto = onPickPhoto,
                onClearPhoto = onClearPhoto,
            )
        }

    }
}

@Composable
private fun HardwareStatusCard(
    diagnostics: com.codex.magicmirrorcontroller.data.MirrorDiagnostics,
    isBusy: Boolean,
    onRefresh: () -> Unit,
) {
    GlassPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Железо Allwinner A733",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(
                onClick = onRefresh,
                enabled = !isBusy,
            ) {
                Text(
                    text = "↻", 
                    color = if (isBusy) PassiveSage else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // CPU Temperature Tile
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "CPU Temp",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = diagnostics.cpuTemp?.let { "${String.format("%.1f", it)}°C" } ?: "N/A",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Uptime Tile
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Uptime",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${diagnostics.uptimeSeconds / 60}m",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlSettingsMenu(

    isBusy: Boolean,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
    onReload: () -> Unit,
    onRefreshAll: () -> Unit,
) {
    OverflowMenu(enabled = !isBusy) { close ->
        DropdownMenuItem(
            text = { Text("Обновить") },
            onClick = {
                close()
                onRefresh()
            },
        )
        DropdownMenuItem(
            text = { Text("Обновить модули") },
            onClick = {
                close()
                onRefreshAll()
            },
        )
        DropdownMenuItem(
            text = { Text("Перезагрузить экран") },
            onClick = {
                close()
                onReload()
            },
        )
        DropdownMenuItem(
            text = { Text("Сменить зеркало") },
            onClick = {
                close()
                onDisconnect()
            },
        )
    }
}

@Composable
private fun StatusCard(
    mirrorState: MirrorState,
    isBusy: Boolean,
    onDisplayAction: (String) -> Unit,
    onDisplayModeChange: (String) -> Unit,
) {
    val displayEnabled = mirrorState.displayState != "off"

    GlassPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Экран",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Light,
                )
            }
            MirrorSwitch(
                checked = displayEnabled,
                onCheckedChange = { checked -> onDisplayAction(if (checked) "on" else "off") },
                enabled = !isBusy,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeButton(
                label = "Зеркало",
                active = mirrorState.displayMode == "mirror",
                enabled = !isBusy && displayEnabled,
                onClick = { onDisplayModeChange("mirror") },
            )
            ModeButton(
                label = "Картины",
                active = mirrorState.displayMode == "gallery",
                enabled = !isBusy && displayEnabled,
                onClick = { onDisplayModeChange("gallery") },
            )
            ModeButton(
                label = "Примерка",
                active = mirrorState.displayMode == "ar",
                enabled = !isBusy && displayEnabled,
                onClick = { onDisplayModeChange("ar") },
            )
        }
    }
}

@Composable
private fun ModeButton(label: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val container by animateColorAsState(
        targetValue = if (active) {
            SoftGreenWhite.copy(alpha = 0.16f)
        } else {
            SkeletonGreen.copy(alpha = if (enabled) 0.42f else 0.24f)
        },
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "mode-container",
    )
    val border by animateColorAsState(
        targetValue = when {
            active -> SoftGreenWhite.copy(alpha = 0.42f)
            enabled -> PassiveSage.copy(alpha = 0.24f)
            else -> PassiveSage.copy(alpha = 0.12f)
        },
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "mode-border",
    )
    val content by animateColorAsState(
        targetValue = when {
            active -> MaterialTheme.colorScheme.onSurface
            enabled -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> PassiveSage.copy(alpha = 0.62f)
        },
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "mode-content",
    )
    val scale by animateFloatAsState(
        targetValue = if (active) 1f else 0.975f,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "mode-scale",
    )

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = container,
        border = BorderStroke(1.dp, border),
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            color = content,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun LayoutCard(
    isBusy: Boolean,
    onOpen: () -> Unit,
) {
    GlassPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Раскладка виджетов",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = onOpen,
                enabled = !isBusy,
                colors = mirrorButtonColors(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text("Править")
            }
        }
    }
}

@Composable
private fun ModuleGrid(
    modules: List<MirrorModule>,
    isBusy: Boolean,
    onToggle: (MirrorModule, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        modules.chunked(2).forEach { rowModules ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowModules.forEach { module ->
                    ModuleTile(
                        module = module,
                        isBusy = isBusy,
                        onToggle = { visible -> onToggle(module, visible) },
                        modifier = Modifier.weight(1f),
                    )
                }

                if (rowModules.size == 1) {
                    Spacer(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ModuleTile(
    module: MirrorModule,
    isBusy: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = moduleTitleRu[module.id] ?: module.title
    val tileColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (module.visible) 0.52f else 0.28f),
        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        label = "module-container",
    )
    val tileBorder by animateColorAsState(
        targetValue = if (module.visible) SoftGreenWhite.copy(alpha = 0.14f) else PassiveSage.copy(alpha = 0.12f),
        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        label = "module-border",
    )
    val titleColor by animateColorAsState(
        targetValue = if (module.visible) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "module-title",
    )
    val tileScale by animateFloatAsState(
        targetValue = if (module.visible) 1f else 0.985f,
        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        label = "module-scale",
    )

    Surface(
        modifier = modifier
            .height(52.dp)
            .graphicsLayer {
                scaleX = tileScale
                scaleY = tileScale
            },
        shape = RoundedCornerShape(18.dp),
        color = tileColor,
        border = BorderStroke(1.dp, tileBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                color = titleColor,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(8.dp))
            MirrorSwitch(
                checked = module.visible,
                onCheckedChange = { onToggle(it) },
                enabled = !isBusy,
            )
        }
    }
}

@Composable
private fun PhotoUploadCard(
    durationMinutes: String,
    photoOverlay: MirrorPhotoOverlay?,
    isBusy: Boolean,
    onDurationChange: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onClearPhoto: () -> Unit,
) {
    GlassPanel {
        Text(
            text = "Фото на зеркало",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.ExtraBold,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = durationMinutes,
                onValueChange = onDurationChange,
                modifier = Modifier.width(96.dp),
                label = { Text("мин") },
                singleLine = true,
            )
            Button(
                onClick = onPickPhoto,
                enabled = !isBusy,
                modifier = Modifier.weight(1f),
                colors = mirrorButtonColors(),
            ) {
                Text("Выбрать фото")
            }
        }

        if (photoOverlay != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "показывается",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onClearPhoto, enabled = !isBusy) {
                    Text("Убрать")
                }
            }
        }
    }
}

@Composable
private fun LayoutEditorOverlay(
    state: MainUiState,
    modules: List<MirrorModule>,
    onMove: (String, MirrorModuleLayout) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f))
            .padding(18.dp)
            .zIndex(20f),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Раскладка", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = "двигай блок, угол меняет размер",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onCancel, enabled = !state.isBusy) {
                    Text("Отмена")
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(34.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black, DeepGreen.copy(alpha = 0.72f), Color.Black),
                        ),
                    )
                    .border(1.dp, VelvetPurple.copy(alpha = 0.52f), RoundedCornerShape(34.dp)),
            ) {
                val previewWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                val previewHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)

                modules.forEach { module ->
                    val layout = state.layoutDraft[module.id] ?: module.layout
                    LayoutEditorModuleChip(
                        module = module,
                        layout = layout,
                        previewWidthPx = previewWidthPx,
                        previewHeightPx = previewHeightPx,
                        onMove = onMove,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onReset,
                    enabled = !state.isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Сбросить")
                }
                Button(
                    onClick = onSave,
                    enabled = !state.isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Сохранить")
                }
            }
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.LayoutEditorModuleChip(
    module: MirrorModule,
    layout: MirrorModuleLayout,
    previewWidthPx: Float,
    previewHeightPx: Float,
    onMove: (String, MirrorModuleLayout) -> Unit,
) {
    val title = moduleTitleRu[module.id] ?: module.title
    val latestLayout by rememberUpdatedState(layout)
    var moveStartLayout by remember(module.id) { mutableStateOf(layout) }
    var moveTotal by remember(module.id) { mutableStateOf(Offset.Zero) }
    var resizeStartLayout by remember(module.id) { mutableStateOf(layout) }
    var resizeTotal by remember(module.id) { mutableStateOf(Offset.Zero) }

    Surface(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = (previewWidthPx * layout.x / 100f).roundToInt(),
                    y = (previewHeightPx * layout.y / 100f).roundToInt(),
                )
            }
            .width(maxWidth * (layout.w / 100f))
            .height(maxHeight * (layout.h / 100f))
            .pointerInput(module.id, previewWidthPx, previewHeightPx) {
                detectDragGestures(
                    onDragStart = {
                        moveStartLayout = latestLayout
                        moveTotal = Offset.Zero
                    },
                    onDragEnd = {
                        moveTotal = Offset.Zero
                    },
                    onDragCancel = {
                        moveTotal = Offset.Zero
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        moveTotal += dragAmount
                        onMove(
                            module.id,
                            moveLayoutByPixels(
                                layout = moveStartLayout,
                                dragX = moveTotal.x,
                                dragY = moveTotal.y,
                                previewWidth = previewWidthPx,
                                previewHeight = previewHeightPx,
                            ),
                        )
                    },
                )
            },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, VelvetPurple.copy(alpha = 0.7f)),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            val labelLength = title.length.coerceAtLeast(4)
            val labelSize = minOf(
                maxHeight.value * 0.42f,
                maxWidth.value / (labelLength * 0.48f),
            ).coerceIn(10f, 34f)

            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = labelSize.sp,
                    lineHeight = (labelSize * 1.05f).sp,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(28.dp)
                    .pointerInput(module.id, "resize", previewWidthPx, previewHeightPx) {
                        detectDragGestures(
                            onDragStart = {
                                resizeStartLayout = latestLayout
                                resizeTotal = Offset.Zero
                            },
                            onDragEnd = {
                                resizeTotal = Offset.Zero
                            },
                            onDragCancel = {
                                resizeTotal = Offset.Zero
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                resizeTotal += dragAmount
                                onMove(
                                    module.id,
                                    resizeLayoutByPixels(
                                        layout = resizeStartLayout,
                                        dragX = resizeTotal.x,
                                        dragY = resizeTotal.y,
                                        previewWidth = previewWidthPx,
                                        previewHeight = previewHeightPx,
                                    ),
                                )
                            },
                        )
                    },
                shape = RoundedCornerShape(topStart = 14.dp, bottomEnd = 12.dp),
                color = VelvetPurple.copy(alpha = 0.86f),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val lineColor = SoftGreenWhite.copy(alpha = 0.86f)
                    val stroke = 2.dp.toPx()
                    val gap = 6.dp.toPx()
                    drawLine(
                        color = lineColor,
                        start = Offset(size.width - gap, size.height * 0.36f),
                        end = Offset(size.width - gap, size.height - gap),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = lineColor,
                        start = Offset(size.width * 0.36f, size.height - gap),
                        end = Offset(size.width - gap, size.height - gap),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
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
            .background(ForestBlack.copy(alpha = 0.94f))
            .padding(22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
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
                        Text("QR-подключение", style = MaterialTheme.typography.headlineSmall)
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
