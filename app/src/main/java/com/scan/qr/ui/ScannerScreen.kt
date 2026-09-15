package com.scan.qr.ui

import android.os.SystemClock
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.scan.qr.scanner.BarcodeHit
import com.scan.qr.scanner.GateResult
import com.scan.qr.scanner.GalleryScanResult
import com.scan.qr.scanner.HitGate
import com.scan.qr.scanner.QrAnalyzer
import com.scan.qr.scanner.QrExecutors
import com.scan.qr.scanner.scanImageForQr
import com.scan.qr.ui.overlay.MultiQrOverlay
import com.scan.qr.ui.overlay.RectF4
import com.scan.qr.ui.overlay.isVisibleInView
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 让分析器在回调里能引用自身以锁定扫描 */
private class AnalyzerRef {
    var analyzer: QrAnalyzer? = null
}

/**
 * 预览区域尺寸。分析线程每帧都要读它，用 @Volatile 保证可见性即可；
 * 不用 Compose State 是因为：State 会在尺寸变化时触发整页重组，
 * 而且会逼着「可见性过滤」回到主线程做，白白占用主线程。
 */
private class PreviewSizeBox {
    @Volatile var width: Int = 0
    @Volatile var height: Int = 0
}

/**
 * 分析帧分辨率上限 1280x960（4:3）。
 * 不设的话 CameraX 会按 4:3 自动挑，很多机器直接给 1920x1440，
 * ML Kit 每帧都要在这么大的图上跑检测 —— 这是扫码卡顿的主因。
 * 1280x960 对二维码识别绰绰有余（还有变焦兜底），帧耗时能明显下降。
 */
private fun analysisResolutionSelector(): ResolutionSelector = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .setResolutionStrategy(
        ResolutionStrategy(
            Size(1280, 960),
            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
        )
    )
    .build()

@Composable
fun ScannerScreen(
    onScanned: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onMultiQrFromGallery: (GalleryScanResult) -> Unit
) {
    val context = LocalContext.current
    // 当前已安装包的版本号 + 安装时间（不是编译时常量，能反映手机上真实装的是哪一版）
    val versionStamp = remember(context) { installedStampLabel(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    // 多码浮层状态
    var multiHits by remember { mutableStateOf<List<BarcodeHit>?>(null) }
    var multiContent by remember { mutableStateOf(IntSize.Zero) }
    // 预览区域尺寸：判断二维码是否真的在屏幕上可见（分析线程读取，非 Compose State）
    val previewBox = remember { PreviewSizeBox() }

    // onScanned 每次重组都是新 lambda；用 rememberUpdatedState 让分析线程永远拿到最新的那个
    val currentOnScanned by rememberUpdatedState(onScanned)

    val gate = remember { HitGate() }
    val analyzerRef = remember { AnalyzerRef() }
    val analyzer = remember {
        QrAnalyzer { hits, width, height ->
            // ===== 以下全部在分析线程执行，主线程只管改 State =====
            // 预览是 FILL_CENTER（左右裁切），分析帧是完整画面：
            // 只接受中心落在可见区域内的码，否则会出现"屏幕上没有却被识别"
            val viewW = previewBox.width
            val viewH = previewBox.height
            val visibleHits = if (viewW > 0 && viewH > 0) {
                hits.filter { hit ->
                    isVisibleInView(
                        RectF4(hit.left, hit.top, hit.right, hit.bottom),
                        width, height, viewW.toFloat(), viewH.toFloat(), crop = true
                    )
                }
            } else {
                hits
            }

            when (val g = gate.onHits(visibleHits, SystemClock.uptimeMillis(), width, height)) {
                GateResult.Ignore -> Unit

                GateResult.Clear -> mainExecutor.execute { multiHits = null }

                is GateResult.Single -> {
                    analyzerRef.analyzer?.paused = true // 锁定，避免同一二维码重复触发
                    mainExecutor.execute {
                        multiHits = null
                        vibrate(context)
                        currentOnScanned(g.raw)
                    }
                }

                is GateResult.Multi -> mainExecutor.execute {
                    multiHits = g.hits
                    multiContent = IntSize(g.contentWidth, g.contentHeight)
                }
            }
        }.also { analyzerRef.analyzer = it }
    }
    // 进程级共享的分析线程：**永不 shutdown**。见 QrExecutors 的注释 ——
    // 提前关掉线程池会让 ML Kit 在帧返回时往已终止的池子提交回调，
    // 主线程抛 RejectedExecutionException 直接闪退（真机踩过）。
    val analysisExecutor = QrExecutors.analysis
    var cameraError by remember { mutableStateOf<String?>(null) }
    var providerRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // ---------- 变焦 ----------
    var cameraRef by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    // 目标倍率放普通对象里（非 Compose 状态）：手势每帧都读，用 State 会整页重组
    val zoomTarget = remember { ZoomTarget() }
    // 倍率文本单独放 State，并且只在 ZoomPill 内部读取 → 只有那个文本重组
    val zoomText = remember { mutableStateOf(zoomLabel(1f)) }
    var minZoom by remember { mutableStateOf(1f) }
    var maxZoom by remember { mutableStateOf(1f) }

    fun applyZoom(target: Float) {
        val camera = cameraRef ?: return
        val ratio = clampZoom(target, minZoom, maxZoom)
        // 死区：微小抖动不下发，避免高频 setZoomRatio 排队造成顿挫
        if (abs(ratio - zoomTarget.ratio) < 0.01f) return
        zoomTarget.ratio = ratio
        val label = zoomLabel(ratio)
        if (label != zoomText.value) zoomText.value = label
        try {
            camera.cameraControl.setZoomRatio(ratio)
        } catch (e: Exception) {
            // 个别设备不支持变焦：忽略
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            providerRef?.unbindAll() // 离开扫码页立刻释放相机
            // analyzer.close() 只是打标记，等最后一帧回调结束才真正关 ML Kit scanner；
            // 线程池是共享的、不在这里 shutdown（否则会闪退，见 QrExecutors 注释）
            analyzer.close()
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = scanImageForQr(context, uri)
            val hits = result?.hits.orEmpty()
            when {
                result == null || hits.isEmpty() ->
                    Toast.makeText(context, "未检测到二维码", Toast.LENGTH_SHORT).show()

                hits.size == 1 -> {
                    vibrate(context)
                    onScanned(hits.first().raw)
                }

                else -> onMultiQrFromGallery(result)
            }
        }
    }

    val activeHits = multiHits

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged {
                previewBox.width = it.width
                previewBox.height = it.height
            }
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // 双指捏合变焦：以本地目标倍率为基准，避免读到异步滞后的 zoomState
                    detectTransformGestures { _, _, zoomChange, _ ->
                        if (zoomChange != 1f) applyZoom(zoomTarget.ratio * zoomChange)
                    }
                },
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    try {
                        val provider = future.get()
                        provider.unbindAll()
                        providerRef = provider

                        // 预览保持设备原生 4:3（保证画质）
                        val preview = Preview.Builder()
                            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                            .build()
                            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                        // 分析帧压到 1280x960（同为 4:3，浮层坐标映射不受影响），
                        // 显著降低 ML Kit 每帧耗时 → 扫码更跟手
                        val analysis = ImageAnalysis.Builder()
                            .setResolutionSelector(analysisResolutionSelector())
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { it.setAnalyzer(analysisExecutor, analyzer) }

                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis
                        ).also { cam ->
                            cameraRef = cam
                            val state = cam.cameraInfo.zoomState.value
                            minZoom = state?.minZoomRatio ?: 1f
                            maxZoom = state?.maxZoomRatio ?: 1f
                            zoomTarget.ratio = clampZoom(state?.zoomRatio ?: 1f, minZoom, maxZoom)
                            zoomText.value = zoomLabel(zoomTarget.ratio)
                        }
                        cameraError = null
                    } catch (e: Exception) {
                        cameraError = "摄像头初始化失败：${e.message ?: "未知错误"}"
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )

        // 单码时的扫描框（多码浮层出现时隐藏，避免干扰）
        if (activeHits == null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (-30).dp)
                    .size(240.dp)
                    .border(2.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(16.dp))
            )
            Text(
                text = "将二维码放入框内",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = 130.dp)
            )
        }

        // 多码：真实位置框 + 指向箭头（点击框或箭头直接选中）
        if (activeHits != null && multiContent.width > 0) {
            MultiQrOverlay(
                contentWidth = multiContent.width,
                contentHeight = multiContent.height,
                boxes = activeHits.map { RectF4(it.left, it.top, it.right, it.bottom) },
                crop = true,
                onSelect = { index ->
                    activeHits.getOrNull(index)?.let { hit ->
                        analyzerRef.analyzer?.paused = true
                        multiHits = null
                        vibrate(context)
                        onScanned(hit.raw)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                hint = "点击箭头或二维码框选择"
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 20.dp, end = 8.dp, top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("二维码扫描", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpenSettings) {
                    Text("设置", color = Color(0xFFD1D5DB), fontSize = 15.sp)
                }
                TextButton(onClick = onOpenHistory) {
                    Text("历史", color = Color(0xFFD1D5DB), fontSize = 15.sp)
                }
            }
        }

        if (activeHits == null) {
            Button(
                onClick = {
                    photoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text("相册", color = Color.White)
            }

            // 变焦：点按切换 1x → 2x → 最大，也可双指捏合
            ZoomPill(
                text = zoomText,
                onCycle = { applyZoom(nextZoom(zoomTarget.ratio, minZoom, maxZoom)) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 20.dp, bottom = 34.dp)
            )
        }

        if (cameraError != null) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(cameraError!!, color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                Text("请检查相机是否被其它应用占用", color = Color(0xFF9CA3AF), fontSize = 12.sp)
            }
        }

        // 版本号 + 安装时间：便于确认手机上装的是新包（旧包没被替换时一眼可见）
        Text(
            text = versionStamp,
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 16.dp, bottom = 28.dp)
        )
    }
}

/**
 * 变焦药丸。倍率文本以 `State` 传入、**在内部才读取**，
 * 这样倍率变化只重组这个小组件，不会带着整个扫码页一起重组（减少手势期间的卡顿）。
 */
@Composable
private fun ZoomPill(
    text: State<String>,
    onCycle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Text(
        text = text.value,
        color = Color.White,
        fontSize = 14.sp,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xCC101010))
            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .clickable { onCycle() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}
