package com.scan.qr.ui.overlay

import kotlin.math.max
import kotlin.math.min

/** 矩形（内容像素坐标或屏幕像素坐标） */
data class RectF4(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun centerX(): Float = (left + right) / 2f
    fun centerY(): Float = (top + bottom) / 2f

    fun overlaps(other: RectF4, margin: Float = 0f): Boolean =
        left < other.right + margin && right + margin > other.left &&
                top < other.bottom + margin && bottom + margin > other.top
}

/** 内容坐标 → 视图坐标的线性变换 */
data class ViewTransform(val scale: Float, val offsetX: Float, val offsetY: Float) {
    fun map(r: RectF4): RectF4 = RectF4(
        r.left * scale + offsetX,
        r.top * scale + offsetY,
        r.right * scale + offsetX,
        r.bottom * scale + offsetY
    )
}

/** ContentScale.Fit：完整显示内容，留黑边 */
fun fitTransform(contentW: Int, contentH: Int, viewW: Float, viewH: Float): ViewTransform {
    if (contentW <= 0 || contentH <= 0 || viewW <= 0f || viewH <= 0f) return ViewTransform(1f, 0f, 0f)
    val s = min(viewW / contentW, viewH / contentH)
    return ViewTransform(s, (viewW - contentW * s) / 2f, (viewH - contentH * s) / 2f)
}

/** FILL_CENTER / CenterCrop：铺满视图，四周裁切 */
fun cropTransform(contentW: Int, contentH: Int, viewW: Float, viewH: Float): ViewTransform {
    if (contentW <= 0 || contentH <= 0 || viewW <= 0f || viewH <= 0f) return ViewTransform(1f, 0f, 0f)
    val s = max(viewW / contentW, viewH / contentH)
    return ViewTransform(s, (viewW - contentW * s) / 2f, (viewH - contentH * s) / 2f)
}

enum class ArrowDirection { LEFT, RIGHT, UP, DOWN }

/**
 * 判断二维码是否“真的在屏幕上看得见”。
 *
 * 预览用 FILL_CENTER（crop）铺满屏幕，会裁掉左右（或上下）超出部分；
 * 而识别发生在完整分析帧上，所以视野边缘的码可能屏幕里根本没有。
 * 用 crop 变换把内容坐标映射到视图坐标后，只有中心落在视图内的才算可见。
 */
fun isVisibleInView(
    box: RectF4,
    contentW: Int,
    contentH: Int,
    viewW: Float,
    viewH: Float,
    crop: Boolean = true
): Boolean {
    if (contentW <= 0 || contentH <= 0 || viewW <= 0f || viewH <= 0f) return true
    val t = if (crop) cropTransform(contentW, contentH, viewW, viewH)
    else fitTransform(contentW, contentH, viewW, viewH)
    val mapped = t.map(box)
    val cx = mapped.centerX()
    val cy = mapped.centerY()
    return cx >= 0f && cx <= viewW && cy >= 0f && cy <= viewH
}

/** 箭头放置结果（视图像素坐标），direction 表示箭头指向二维码的方向 */
data class ArrowPlacement(
    val index: Int,
    val rect: RectF4,
    val direction: ArrowDirection
)

/**
 * 在二维码旁边放置指向它的箭头。
 * 依次尝试放在右侧 / 左侧 / 下方 / 上方，取第一个“在屏幕内、不压其它二维码、不与已放箭头重叠”的位置。
 * 四个方向都放不下时退回可用空间最大的一侧（仍保证在屏幕内）。
 */
fun placeArrows(
    boxes: List<RectF4>,
    viewW: Float,
    viewH: Float,
    arrowSize: Float,
    gap: Float,
    margin: Float = 6f
): List<ArrowPlacement> {
    val placements = mutableListOf<ArrowPlacement>()
    val used = mutableListOf<RectF4>()

    boxes.forEachIndexed { index, box ->
        val sides = listOf(
            ArrowDirection.LEFT to RectF4(box.right + gap, box.centerY() - arrowSize / 2f, box.right + gap + arrowSize, box.centerY() + arrowSize / 2f),
            ArrowDirection.RIGHT to RectF4(box.left - gap - arrowSize, box.centerY() - arrowSize / 2f, box.left - gap, box.centerY() + arrowSize / 2f),
            ArrowDirection.UP to RectF4(box.centerX() - arrowSize / 2f, box.bottom + gap, box.centerX() + arrowSize / 2f, box.bottom + gap + arrowSize),
            ArrowDirection.DOWN to RectF4(box.centerX() - arrowSize / 2f, box.top - gap - arrowSize, box.centerX() + arrowSize / 2f, box.top - gap)
        ).map { (dir, rect) -> dir to clampToView(rect, viewW, viewH, arrowSize) }

        val others = boxes.filterIndexed { i, _ -> i != index }
        val free = sides.firstOrNull { (_, rect) ->
            !used.any { rect.overlaps(it, margin) } && others.none { rect.overlaps(it, margin) }
        }
        val chosen = free ?: sides.minByOrNull { (_, rect) ->
            used.count { rect.overlaps(it, margin) } + others.count { rect.overlaps(it, margin) }
        }!!
        placements += ArrowPlacement(index, chosen.second, chosen.first)
        used += chosen.second
    }
    return placements
}

/** 把箭头矩形限制在视图内（靠边二维码时自动调整位置） */
private fun clampToView(rect: RectF4, viewW: Float, viewH: Float, size: Float): RectF4 {
    if (viewW <= size || viewH <= size) return rect
    val left = rect.left.coerceIn(0f, viewW - size)
    val top = rect.top.coerceIn(0f, viewH - size)
    return RectF4(left, top, left + size, top + size)
}

/**
 * 计算箭头应指的方向：由箭头中心指向二维码中心。
 * 水平方向优先（左右），水平距离很小时才用上下，保证箭头语义清晰。
 */
fun arrowDirection(arrow: RectF4, target: RectF4): ArrowDirection {
    val dx = target.centerX() - arrow.centerX()
    val dy = target.centerY() - arrow.centerY()
    return if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) {
        if (dx >= 0) ArrowDirection.RIGHT else ArrowDirection.LEFT
    } else {
        if (dy >= 0) ArrowDirection.DOWN else ArrowDirection.UP
    }
}
