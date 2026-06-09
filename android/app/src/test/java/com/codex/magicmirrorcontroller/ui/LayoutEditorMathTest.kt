package com.codex.magicmirrorcontroller.ui

import com.codex.magicmirrorcontroller.data.MirrorModuleLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutEditorMathTest {
    @Test
    fun clampsLayoutInsideScreen() {
        val layout = clampLayout(MirrorModuleLayout(x = 90f, y = -4f, w = 20f, h = 10f))

        assertEquals(80f, layout.x, 0.001f)
        assertEquals(0f, layout.y, 0.001f)
    }

    @Test
    fun convertsDragPixelsToPercent() {
        val layout = moveLayoutByPixels(
            layout = MirrorModuleLayout(x = 10f, y = 20f, w = 30f, h = 10f),
            dragX = 45f,
            dragY = -80f,
            previewWidth = 900f,
            previewHeight = 1600f,
        )

        assertEquals(15f, layout.x, 0.001f)
        assertEquals(15f, layout.y, 0.001f)
    }

    @Test
    fun resizesDragPixelsToPercent() {
        val layout = resizeLayoutByPixels(
            layout = MirrorModuleLayout(x = 10f, y = 20f, w = 30f, h = 10f),
            dragX = 180f,
            dragY = 160f,
            previewWidth = 900f,
            previewHeight = 1600f,
        )

        assertEquals(50f, layout.w, 0.001f)
        assertEquals(20f, layout.h, 0.001f)
    }

    @Test
    fun resizeKeepsWidgetInsideScreen() {
        val layout = resizeLayoutByPixels(
            layout = MirrorModuleLayout(x = 70f, y = 92f, w = 20f, h = 6f),
            dragX = 900f,
            dragY = 1600f,
            previewWidth = 900f,
            previewHeight = 1600f,
        )

        assertEquals(30f, layout.w, 0.001f)
        assertEquals(8f, layout.h, 0.001f)
    }
}
