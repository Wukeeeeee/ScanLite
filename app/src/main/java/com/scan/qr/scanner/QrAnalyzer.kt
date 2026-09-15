package com.scan.qr.scanner

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 进程级共享的串行执行器（daemon 线程），**永不 shutdown**。
 *
 * ⚠️ 为什么绝对不能 shutdown（2026-09-15 真机闪退的根因，别再踩）：
 * ML Kit 的 `Task.addOnSuccessListener(executor) { }` 内部会把监听器的派发任务
 * 通过 `executor.execute(...)` 提交给这个 executor（栈里是
 * `com.google.android.gms.tasks.zzw.zzb` → `Executors$DelegatedExecutorService.execute`）。
 * 如果我们在**还有 `process()` 在途**时就 `shutdown()` 了 executor，等那一帧回来，
 * GMS 就会往已终止的池子提交任务 → `RejectedExecutionException`；而且这条链是在
 * **主线程**上跑的（`Handler.handleCallback` → `zzw.addOnSuccessListener`），
 * 异常直接冒到 `Looper.loop` → FATAL EXCEPTION，App 当场闪退。
 *
 * 典型触发时序：扫到码 → 主线程跳结果页 → `DisposableEffect.onDispose` 里
 * `analyzer.close()` 关线程池，但 CameraX 刚提交的那一帧还在 ML Kit 里跑 → 崩。
 * 因为结果早已写进历史，用户「回来一看记录又在」。
 *
 * 共享 + daemon + 永不关闭 = 线程数恒定为 2，且这类竞态从根上不可能发生。
 */
internal object QrExecutors {
    /** ML Kit 结果回调线程：保证判定逻辑串行、无并发问题 */
    val callback: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "qr-result").apply { isDaemon = true }
    }

    /** CameraX 分析线程：同一时刻只有一个扫码页在跑，共用一条就够 */
    val analysis: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "qr-frame").apply { isDaemon = true }
    }
}

/**
 * ML Kit 二维码分析器。在 CameraX 分析线程执行，不阻塞 UI。
 * 返回每帧的全部二维码（含真实位置），由上层决定单码直接进结果 / 多码进入可视化选择。
 * [paused] 为 true 时锁定（已命中或用户已选择），避免重复触发。
 *
 * 注意：ML Kit 的 `addOnSuccessListener` 默认回调在**主线程**。
 * 这里显式指定 [QrExecutors.callback]，让坐标换算与后续判定都在后台线程完成，
 * 主线程只负责改 Compose State —— 这是扫码流畅度的关键之一。
 */
class QrAnalyzer(
    private val onResult: (hits: List<BarcodeHit>, contentWidth: Int, contentHeight: Int) -> Unit
) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    /** 在途的 `process()` 帧数。close() 必须等它归零才真正关 scanner */
    private val inFlight = AtomicInteger(0)
    private val scannerClosed = AtomicBoolean(false)

    @Volatile
    private var closed = false

    @Volatile
    var paused: Boolean = false

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        if (closed || paused) {
            imageProxy.close()
            return
        }
        // 丢弃过期帧：分析有延迟，否则你已把手机移开，旧帧的结果才回来
        val ageMs =
            (android.os.SystemClock.elapsedRealtimeNanos() - imageProxy.imageInfo.timestamp) / 1_000_000
        if (ageMs > STALE_FRAME_MS) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val bufferW = imageProxy.width
        val bufferH = imageProxy.height
        // ML Kit 会按 rotationDegrees 把图像转正后再输出坐标，所以坐标系是转正后的尺寸
        val uprightW = if (rotation == 90 || rotation == 270) bufferH else bufferW
        val uprightH = if (rotation == 90 || rotation == 270) bufferW else bufferH

        val image = InputImage.fromMediaImage(mediaImage, rotation)
        inFlight.incrementAndGet()
        try {
            scanner.process(image)
                .addOnSuccessListener(QrExecutors.callback) { barcodes ->
                    // 回调体自己也要兜住异常：这里抛出去同样是主线程 FATAL
                    try {
                        val hits = barcodes.mapNotNull { it.toHit(uprightW, uprightH) }
                        onResult(hits, uprightW, uprightH)
                    } catch (t: Throwable) {
                        // 单帧处理失败不影响继续扫码
                    }
                }
                .addOnFailureListener(QrExecutors.callback) {
                    // 单帧识别失败（图像损坏等）：忽略，下一帧继续
                }
                .addOnCompleteListener(QrExecutors.callback) {
                    imageProxy.close()
                    onFrameDone()
                }
        } catch (t: Throwable) {
            // process() 同步抛（例如 scanner 刚被关闭）→ 自己收尾，别让帧泄漏
            imageProxy.close()
            onFrameDone()
        }
    }

    /**
     * 停止分析。**不会**在还有帧在途时强行关 scanner / 关线程池 ——
     * 那样会触发上面注释里描述的 RejectedExecutionException 闪退。
     * 真正的关闭推迟到最后一帧回调结束。
     */
    fun close() {
        closed = true
        if (inFlight.get() == 0) closeScanner()
    }

    private fun onFrameDone() {
        if (inFlight.decrementAndGet() == 0 && closed) closeScanner()
    }

    private fun closeScanner() {
        if (scannerClosed.compareAndSet(false, true)) {
            try {
                scanner.close()
            } catch (t: Throwable) {
                // 关闭失败无需处理，进程退出即回收
            }
        }
    }

    private companion object {
        /** 超过该时长的帧直接丢弃（≈ 18 帧 @60Hz），避免“已移开还被识别”；慢机可适当调大 */
        const val STALE_FRAME_MS = 300L
    }
}

private fun Barcode.toHit(uprightW: Int, uprightH: Int): BarcodeHit? {
    val value = rawValue?.takeIf { it.isNotBlank() } ?: return null
    val box = boundingBox
    val left = box?.left?.toFloat() ?: 0f
    val top = box?.top?.toFloat() ?: 0f
    val right = box?.right?.toFloat() ?: uprightW.toFloat()
    val bottom = box?.bottom?.toFloat() ?: uprightH.toFloat()
    return BarcodeHit(
        raw = value,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        contentWidth = uprightW,
        contentHeight = uprightH
    )
}
