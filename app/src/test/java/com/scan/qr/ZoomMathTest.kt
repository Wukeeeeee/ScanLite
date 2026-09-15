package com.scan.qr

import com.scan.qr.ui.clampZoom
import com.scan.qr.ui.nextZoom
import com.scan.qr.ui.zoomLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomMathTest {

    @Test
    fun zoomIsClampedToDeviceRange() {
        assertEquals(1f, clampZoom(0.5f, 1f, 4f), 1e-4f)
        assertEquals(4f, clampZoom(10f, 1f, 4f), 1e-4f)
        assertEquals(2.5f, clampZoom(2.5f, 1f, 4f), 1e-4f)
    }

    @Test
    fun invalidRangeOrNaNDoesNotCrash() {
        assertEquals(1f, clampZoom(3f, 4f, 1f), 1e-4f)
        assertEquals(1f, clampZoom(3f, 0f, 0f), 1e-4f)
        assertEquals(1f, clampZoom(Float.NaN, 1f, 4f), 1e-4f)
    }

    @Test
    fun tapCyclesOneTwoMaxBackToOne() {
        assertEquals(2f, nextZoom(1f, 1f, 8f), 1e-4f)
        assertEquals(8f, nextZoom(2f, 1f, 8f), 1e-4f)
        assertEquals(1f, nextZoom(8f, 1f, 8f), 1e-4f)
    }

    @Test
    fun tapDoesNotExceedDeviceMax() {
        // 设备最大只有 1.5x（例如部分机型）
        assertEquals(1.5f, nextZoom(1f, 1f, 1.5f), 1e-4f)
        assertEquals(1f, nextZoom(1.5f, 1f, 1.5f), 1e-4f)
    }

    @Test
    fun noZoomDeviceStaysAtOne() {
        assertEquals(1f, nextZoom(1f, 1f, 1f), 1e-4f)
    }

    @Test
    fun labelShowsOneDecimal() {
        assertEquals("1.0x", zoomLabel(1f))
        assertEquals("2.5x", zoomLabel(2.5f))
    }
}
