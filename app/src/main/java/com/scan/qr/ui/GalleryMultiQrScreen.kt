package com.scan.qr.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scan.qr.scanner.GalleryScanResult
import com.scan.qr.ui.overlay.MultiQrOverlay
import com.scan.qr.ui.overlay.RectF4

/**
 * 相册一图多码：图片按 Fit 完整显示，在每个二维码真实位置画框 + 指向箭头，点击即选中。
 * 不使用数字编号。
 */
@Composable
fun GalleryMultiQrScreen(
    result: GalleryScanResult,
    onSelect: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val bitmap: Bitmap = result.bitmap

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        MultiQrOverlay(
            contentWidth = bitmap.width,
            contentHeight = bitmap.height,
            boxes = result.hits.map {
                RectF4(it.left, it.top, it.right, it.bottom)
            },
            crop = false,
            onSelect = { index ->
                result.hits.getOrNull(index)?.let {
                    vibrate(context)
                    onSelect(it.raw)
                }
            },
            modifier = Modifier.fillMaxSize(),
            hint = "点击箭头或二维码框选择"
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 8.dp, end = 16.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) {
                Text("返回", color = Color(0xFFD1D5DB), fontSize = 15.sp)
            }
            Text(
                "检测到 ${result.hits.size} 个二维码",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
