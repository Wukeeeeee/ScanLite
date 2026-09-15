package com.scan.qr

import com.scan.qr.ui.overlay.RectF4
import com.scan.qr.ui.overlay.isVisibleInView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 屏幕可见性过滤：预览按 FILL_CENTER（crop）显示，左右会被裁掉，
 * 分析帧里这些被裁掉区域内的二维码屏幕上根本看不见，必须过滤掉。
 */
class VisibilityFilterTest {

    // 竖屏 1080x2400，分析/预览流 4:3
    private val viewW = 1080f
    private val viewH = 2400f

    @Test
    fun centeredQrIsVisible() {
        val box = RectF4(500f, 500f, 700f, 700f) // 画面正中
        assertTrue(isVisibleInView(box, 1440, 1080, viewW, viewH, crop = true))
    }

    @Test
    fun qrCroppedOutSidewaysIsNotVisible() {
        // 4:3 画面铺满 1080x2400 → scale = 2400/1080 = 2.222，
        // 内容宽 1440*2.222=3200，左右各裁掉 (3200-1080)/2 = 1060
        // 所以内容 x < 1060/2.222 ≈ 477 的部分屏幕上看不到
        val offscreen = RectF4(20f, 500f, 200f, 700f)
        assertFalse(isVisibleInView(offscreen, 1440, 1080, viewW, viewH, crop = true))
    }

    @Test
    fun sameQrWouldBeVisibleIfPreviewFitWholeFrame() {
        // 同一位置在 Fit 显示（不裁切）下是可见的 —— 说明差异来自 FILL_CENTER 裁切
        val box = RectF4(20f, 500f, 200f, 700f)
        assertTrue(isVisibleInView(box, 1440, 1080, viewW, viewH, crop = false))
    }

    @Test
    fun rightEdgeCroppedOutIsNotVisible() {
        val offscreen = RectF4(1400f, 500f, 1440f, 700f)
        assertFalse(isVisibleInView(offscreen, 1440, 1080, viewW, viewH, crop = true))
    }

    @Test
    fun degenerateSizesAreTreatedAsVisible() {
        // 还没测量到视图尺寸时不要误杀
        assertTrue(isVisibleInView(RectF4(0f, 0f, 10f, 10f), 1440, 1080, 0f, 0f))
    }
}
