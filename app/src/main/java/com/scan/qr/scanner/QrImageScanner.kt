package com.scan.qr.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 相册图片识别结果：已转正的位图 + 全部二维码（坐标即该位图坐标） */
data class GalleryScanResult(
    val bitmap: Bitmap,
    val hits: List<BarcodeHit>
)

/** 相册图片识别（支持一图多码，二维码与条形码共用 [SCAN_FORMATS]） */
suspend fun scanImageForQr(context: Context, uri: Uri): GalleryScanResult? =
    withContext(Dispatchers.Default) {
        val bitmap = loadUprightBitmap(context, uri) ?: return@withContext null
        val scanner = BarcodeScanning.getClient(scanOptions())
        try {
            val latch = CountDownLatch(1)
            var hits: List<BarcodeHit> = emptyList()
            scanner.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { barcodes: List<Barcode> ->
                    hits = barcodes.mapNotNull { barcode ->
                        val value = barcode.rawValue?.takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        val box = barcode.boundingBox
                        BarcodeHit(
                            raw = value,
                            left = box?.left?.toFloat() ?: 0f,
                            top = box?.top?.toFloat() ?: 0f,
                            right = box?.right?.toFloat() ?: bitmap.width.toFloat(),
                            bottom = box?.bottom?.toFloat() ?: bitmap.height.toFloat(),
                            contentWidth = bitmap.width,
                            contentHeight = bitmap.height
                        )
                    }
                }
                .addOnCompleteListener { latch.countDown() }
            latch.await(15, TimeUnit.SECONDS)
            GalleryScanResult(bitmap, hits)
        } catch (e: Exception) {
            GalleryScanResult(bitmap, emptyList())
        } finally {
            scanner.close()
        }
    }

/**
 * 解码图片并保证“正立”：ImageDecoder 自动处理 EXIF；
 * API < 28 时用 BitmapFactory + ExifInterface 手动旋转。
 */
private fun loadUprightBitmap(context: Context, uri: Uri): Bitmap? {
    if (Build.VERSION.SDK_INT >= 28) {
        try {
            return ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(context.contentResolver, uri)
            ) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: Exception) {
            // 落到下面的兼容实现
        }
    }
    return try {
        val raw = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: return null
        val orientation = context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
        applyExif(raw, orientation)
    } catch (e: Exception) {
        null
    }
}

@Suppress("DEPRECATION")
private fun applyExif(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
        }

        else -> return bitmap
    }
    return try {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    } catch (e: Exception) {
        bitmap
    }
}
