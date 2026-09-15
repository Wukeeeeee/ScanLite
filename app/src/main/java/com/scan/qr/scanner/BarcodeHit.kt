package com.scan.qr.scanner

/** 单个二维码命中结果：内容 + 在图像坐标系中的真实位置 */
data class BarcodeHit(
    val raw: String,
    /** 在“已转正”图像坐标系中的位置（ML Kit 已按 rotationDegrees 旋转后的坐标系） */
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** 转正后图像宽高，用于把坐标映射到屏幕 */
    val contentWidth: Int,
    val contentHeight: Int
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** 位置是否有效（ML Kit 偶尔返回空 box） */
    val hasBox: Boolean get() = width > 0f && height > 0f
}
