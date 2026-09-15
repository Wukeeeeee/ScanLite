package com.scan.qr

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.scan.qr.scanner.QrActions
import com.scan.qr.scanner.label
import com.scan.qr.scanner.parseQrContent
import com.scan.qr.scanner.planLabel
import com.scan.qr.scanner.routingKind
import com.scan.qr.ui.ResultScreen

/**
 * 仅 debug 变体：E2E 联调用的「结果页宿主」。
 *
 * 存在的理由：真机 E2E 想覆盖任意二维码内容，但走相机要人举着手机、走相册要跟系统
 * 照片选择器较劲（它的排序不受控）。这里直接把**线上同一个 [ResultScreen]** 起出来，
 * 内容用 adb 传进去 —— 分流逻辑、按钮文案、真实的 `QrActions.open()` 全都是生产代码，
 * 只有「内容从哪来」这一步被替换掉。
 *
 * ```
 * adb shell "am start -n com.scan.qr/.E2eActivity --es raw 'https://qr.alipay.com/fkx1a1b2c3d4e5f6'"
 * adb logcat -d -s QRE2E:I          # 打印 type / routingKind / plan / 按钮文案
 * ```
 * release 包里不存在这个类（`aapt2 dump xmltree` 可复核）。
 */
class E2eActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = intent?.getStringExtra("raw").orEmpty()
        val content = parseQrContent(raw)

        // 把分流结论打到日志：这三行就是「为什么点下去是这个结果」的完整依据
        val kind = routingKind(content.raw, content.type)
        val plan = QrActions.linkPlan(this, content)
        val label = plan?.let { planLabel(it) } ?: QrActions.primaryLabel(content)
        Log.i(TAG, "raw        = $raw")
        Log.i(TAG, "type       = ${content.type}")
        Log.i(TAG, "routingKind= $kind (${kind.label()})")
        Log.i(TAG, "plan       = $plan")
        Log.i(TAG, "button     = $label")

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ResultScreen(
                    content = content,
                    onRescan = {},
                    onOpenHistory = {},
                    onOpenSettings = {}
                )
            }
        }
    }

    private companion object {
        const val TAG = "QRE2E"
    }
}
