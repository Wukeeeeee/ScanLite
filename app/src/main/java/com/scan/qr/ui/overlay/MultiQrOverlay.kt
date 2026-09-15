package com.scan.qr.ui.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private val Accent = Color(0xFF00E5FF)

/**
 * 多二维码选择浮层：
 * 在每个二维码的真实位置画识别框（可直接点击），并在旁边放一个指向它的箭头（点击同样进入结果）。
 * 不使用任何数字编号。
 *
 * @param crop true = 背景按 FILL_CENTER 铺满（相机预览），false = 按 Fit 完整显示（相册图片）
 */
@Composable
fun MultiQrOverlay(
    contentWidth: Int,
    contentHeight: Int,
    boxes: List<RectF4>,
    crop: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val arrowSize = with(density) { 60.dp.toPx() }
    val gap = with(density) { 8.dp.toPx() }
    val strokeWidth = with(density) { 3.dp.toPx() }

    Box(modifier.onSizeChanged { viewSize = it }) {
        if (viewSize.width == 0 || viewSize.height == 0 || boxes.isEmpty()) return@Box

        val viewW = viewSize.width.toFloat()
        val viewH = viewSize.height.toFloat()
        val transform = if (crop) {
            cropTransform(contentWidth, contentHeight, viewW, viewH)
        } else {
            fitTransform(contentWidth, contentHeight, viewW, viewH)
        }
        val mapped = boxes.map { transform.map(it) }

        // 识别框：点击即选中该二维码
        mapped.forEachIndexed { index, rect ->
            Box(
                modifier = Modifier
                    .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                    .size(
                        width = with(density) { rect.width.toDp() },
                        height = with(density) { rect.height.toDp() }
                    )
                    .border(
                        width = 3.dp,
                        color = Accent,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .clickable { onSelect(index) }
            )
        }

        // 箭头：指向各自二维码，点击同为选中
        placeArrows(mapped, viewW, viewH, arrowSize, gap).forEach { placement ->
            ArrowButton(
                placement = placement,
                sizePx = arrowSize,
                strokeWidth = strokeWidth,
                onSelect = onSelect
            )
        }

        hint?.let {
            Text(
                text = it,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp)
            )
        }
    }
}

@Composable
private fun ArrowButton(
    placement: ArrowPlacement,
    sizePx: Float,
    strokeWidth: Float,
    onSelect: (Int) -> Unit
) {
    val density = LocalDensity.current
    val sizeDp = with(density) { sizePx.toDp() }
    Box(
        modifier = Modifier
            .offset {
                IntOffset(placement.rect.left.roundToInt(), placement.rect.top.roundToInt())
            }
            .size(sizeDp)
            .clip(CircleShape)
            .background(Color(0xCC101010))
            .border(2.dp, Accent, CircleShape)
            .clickable { onSelect(placement.index) },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(with(density) { (sizePx * 0.58f).toDp() })) {
            drawArrow(placement.direction, Accent, strokeWidth * 0.9f)
        }
    }
}

private fun DrawScope.drawArrow(direction: ArrowDirection, color: Color, strokeWidth: Float) {
    val w = size.width
    val h = size.height
    val head = w * 0.34f

    when (direction) {
        ArrowDirection.RIGHT, ArrowDirection.LEFT -> {
            val toRight = direction == ArrowDirection.RIGHT
            val startX = if (toRight) w * 0.10f else w * 0.90f
            val tipX = if (toRight) w * 0.90f else w * 0.10f
            val headBaseX = tipX - if (toRight) head else -head
            drawLine(
                color = color,
                start = Offset(startX, h / 2f),
                end = Offset(headBaseX, h / 2f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            val path = Path().apply {
                moveTo(tipX, h / 2f)
                lineTo(headBaseX, h / 2f - w * 0.24f)
                lineTo(headBaseX, h / 2f + w * 0.24f)
                close()
            }
            drawPath(path, color)
        }

        ArrowDirection.DOWN, ArrowDirection.UP -> {
            val toBottom = direction == ArrowDirection.DOWN
            val startY = if (toBottom) h * 0.10f else h * 0.90f
            val tipY = if (toBottom) h * 0.90f else h * 0.10f
            val headBaseY = tipY - if (toBottom) head else -head
            drawLine(
                color = color,
                start = Offset(w / 2f, startY),
                end = Offset(w / 2f, headBaseY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            val path = Path().apply {
                moveTo(w / 2f, tipY)
                lineTo(w / 2f - w * 0.24f, headBaseY)
                lineTo(w / 2f + w * 0.24f, headBaseY)
                close()
            }
            drawPath(path, color)
        }
    }
}
