package com.scan.qr.ui

/** 变焦倍率夹取：落在设备支持的 [min, max] 内；区间非法或 NaN 时回退到 min */
fun clampZoom(target: Float, min: Float, max: Float): Float {
    if (min <= 0f || max <= 0f || max < min) return 1f
    if (target.isNaN()) return min
    return target.coerceIn(min, max)
}

/** 点按循环切换：1x → 2x → 设备最大 → 1x（设备不支持变焦时恒为 min） */
fun nextZoom(current: Float, min: Float, max: Float): Float {
    val c = clampZoom(current, min, max)
    return when {
        max <= min + 0.01f -> clampZoom(min, min, max) // 不支持变焦
        c < 1.5f -> clampZoom(2f, min, max) // 1x → 2x（设备最大不足 2x 时取最大）
        c < max - 0.05f -> clampZoom(max, min, max) // 2x → 最大
        else -> clampZoom(min, min, max) // 最大 → 1x
    }
}

/** 显示用文本，例如 1.0x / 2.5x */
fun zoomLabel(ratio: Float): String = String.format("%.1fx", ratio)

/**
 * 变焦目标保持器：存在 `remember` 里、**刻意不参与 Compose 状态**。
 *
 * 为什么不用 mutableStateOf：手势每帧都要读"当前目标倍率"，用 State 会让整个扫码页每帧重组。
 * 为什么不用 `cameraControl.zoomState.value`：CameraX 的 zoomState 是**异步**更新的，
 * 手势中读到的常是上一帧的旧值 → `旧值 * zoomChange` 反复累加 → 倍率来回跳，看起来就是"卡顿"。
 * 所以：以本地目标值为唯一基准，只把它下发给相机。
 */
class ZoomTarget {
    var ratio: Float = 1f
}
