package com.scan.qr

import com.google.mlkit.vision.barcode.common.Barcode
import com.scan.qr.scanner.SCAN_FORMATS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 码制清单的回归锁。
 *
 * 起因：实时扫描与相册识别原本各自写了一遍 `setBarcodeFormats`，只改一处就会变成
 * 「摄像头扫得出、相册扫不出」。现在两条路径统一用 [SCAN_FORMATS]，这个测试保证
 * 清单不会被悄悄改回「只支持二维码」。
 */
class ScanFormatsTest {

    @Test
    fun qrCodeIsAlwaysSupported() {
        assertTrue(Barcode.FORMAT_QR_CODE in SCAN_FORMATS)
    }

    @Test
    fun commonOneDimensionalFormatsAreSupported() {
        val expected = listOf(
            Barcode.FORMAT_EAN_13,   // 国内最常见的商品条码
            Barcode.FORMAT_EAN_8,
            Barcode.FORMAT_UPC_A,
            Barcode.FORMAT_UPC_E,
            Barcode.FORMAT_CODE_128, // 快递单 / 物料码
            Barcode.FORMAT_CODE_39,
            Barcode.FORMAT_ITF,      // 图书 / 物流
            Barcode.FORMAT_CODABAR
        )
        expected.forEach { assertTrue("缺少码制 $it", it in SCAN_FORMATS) }
    }

    @Test
    fun industrialFormatsAreLeftOut() {
        // 加了不报错，但每帧解码都要多试一遍，日常又扫不到 → 刻意排除
        val excluded = listOf(
            Barcode.FORMAT_AZTEC,
            Barcode.FORMAT_DATA_MATRIX,
            Barcode.FORMAT_PDF417
        )
        excluded.forEach { assertTrue("不该包含 $it", it !in SCAN_FORMATS) }
    }

    @Test
    fun formatListHasNoDuplicates() {
        assertEquals(SCAN_FORMATS.size, SCAN_FORMATS.toSet().size)
    }
}
