package com.codex.magicmirrorcontroller.ui

import com.codex.magicmirrorcontroller.data.MirrorModuleLayout
import kotlin.math.max
import kotlin.math.min

private const val MinLayoutWidth = 16f
private const val MinLayoutHeight = 5f

fun clampLayout(layout: MirrorModuleLayout): MirrorModuleLayout {
    val width = min(max(layout.w, MinLayoutWidth), 100f)
    val height = min(max(layout.h, MinLayoutHeight), 100f)
    val maxX = max(0f, 100f - width)
    val maxY = max(0f, 100f - height)

    return layout.copy(
        w = width,
        h = height,
        x = min(max(layout.x, 0f), maxX),
        y = min(max(layout.y, 0f), maxY),
    )
}

fun moveLayoutByPixels(
    layout: MirrorModuleLayout,
    dragX: Float,
    dragY: Float,
    previewWidth: Float,
    previewHeight: Float,
): MirrorModuleLayout {
    if (previewWidth <= 0f || previewHeight <= 0f) {
        return layout
    }

    return clampLayout(
        layout.copy(
            x = layout.x + dragX / previewWidth * 100f,
            y = layout.y + dragY / previewHeight * 100f,
        ),
    )
}

fun resizeLayoutByPixels(
    layout: MirrorModuleLayout,
    dragX: Float,
    dragY: Float,
    previewWidth: Float,
    previewHeight: Float,
): MirrorModuleLayout {
    if (previewWidth <= 0f || previewHeight <= 0f) {
        return layout
    }

    val maxWidth = max(MinLayoutWidth, 100f - layout.x)
    val maxHeight = max(MinLayoutHeight, 100f - layout.y)
    val width = min(max(layout.w + dragX / previewWidth * 100f, MinLayoutWidth), maxWidth)
    val height = min(max(layout.h + dragY / previewHeight * 100f, MinLayoutHeight), maxHeight)

    return clampLayout(
        layout.copy(
            w = width,
            h = height,
        ),
    )
}
