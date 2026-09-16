package com.scan.qr.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scan.qr.scanner.AltApp
import com.scan.qr.scanner.BrowserPref
import com.scan.qr.scanner.LinkPlan
import com.scan.qr.scanner.OpenResult
import com.scan.qr.scanner.QrActions
import com.scan.qr.scanner.QrContent
import com.scan.qr.scanner.QrType
import com.scan.qr.scanner.altAppsForContent
import com.scan.qr.scanner.categoryLabel
import com.scan.qr.scanner.label
import com.scan.qr.scanner.planHint
import com.scan.qr.scanner.planLabel

/**
 * 结果页：先显示类型与内容，任何外跳都必须用户主动点击。
 */
@Composable
fun ResultScreen(
    content: QrContent,
    onRescan: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // 当前默认浏览器（在设置页里选）；null = 每次询问
    val browserName = remember { BrowserPref.preferredLabel(context) }
    // 链接类的打开计划（微信码 → 复制+拉起微信、支付宝码 → 交给支付宝、其它 App 直达 / 浏览器 / 选择器）
    val plan = remember(content) { QrActions.linkPlan(context, content) }
    val primaryLabel = plan?.let { planLabel(it) } ?: QrActions.primaryLabel(content)
    // 应用内浏览器选择器：系统选择器在国产 ROM 上只会列出系统浏览器，这里自己列
    var showBrowserPicker by remember { mutableStateOf(false) }
    val browsers = remember(content) { BrowserPref.listBrowsers(context) }
    // 应用内「选择其他应用打开」候选。
    // 不能用系统选择器：单一候选时 AOSP 的 ChooserActivity 直接启动、不弹界面，
    // 而 ColorOS 对 http/https 只查得到系统浏览器一个 → 会静默变成「直接用浏览器打开」。
    var showAppPicker by remember { mutableStateOf(false) }
    val chooserApps = remember(content) { QrActions.chooserApps(context, content) }
    val pickerTitle = QrActions.chooserTitle(content)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("扫描结果", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            androidx.compose.material3.TextButton(onClick = onOpenHistory) {
                Text("历史", color = Color(0xFFD1D5DB), fontSize = 15.sp)
            }
        }

        Spacer(Modifier.height(20.dp))

        // 类型（微信专属内容按「分流结果」显示，否则会显示成「网页链接」自相矛盾）
        Text(
            categoryLabel(content),
            color = Color(0xFF9CA3AF),
            fontSize = 13.sp
        )
        Spacer(Modifier.height(10.dp))

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            if (content.type == QrType.UNKNOWN) {
                Text("无法安全处理此内容。", color = Color(0xFFF87171), fontSize = 15.sp)
                Spacer(Modifier.height(12.dp))
            }

            SelectionContainer {
                Text(
                    content.display.ifBlank { content.raw },
                    color = Color(0xFFE5E7EB),
                    fontSize = 17.sp
                )
            }

            content.wifi?.let { wifi ->
                Spacer(Modifier.height(14.dp))
                InfoLine("网络名称", wifi.ssid)
                InfoLine("安全类型", wifi.security)
                wifi.password?.let { InfoLine("密码", it) }
                if (wifi.hidden) InfoLine("隐藏网络", "是")
            } ?: content.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                Spacer(Modifier.height(14.dp))
                Text(detail, color = Color(0xFF9CA3AF), fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(16.dp))

        // 主操作：仅对可安全外跳的类型显示，且必须用户点击
        if (QrActions.hasPrimaryAction(content)) {
            Button(
                onClick = {
                    // 微信专用码：先复制内容，拉起微信后用户直接用「扫一扫」或粘贴
                    val isWeChatCode = plan is LinkPlan.WeChatCode
                    if (isWeChatCode) {
                        clipboard.setText(AnnotatedString(content.raw))
                    }
                    val r = QrActions.open(context, content)
                    handleOpenResult(context, r)
                    if (r == OpenResult.APP_OPENED) {
                        // App 只是被拉起来，没直达具体页面：告诉用户内容已经复制好
                        val msg = when {
                            isWeChatCode -> "内容已复制，请在微信里点「扫一扫」"
                            plan is LinkPlan.AppLink -> "已打开${plan.appName}，链接已复制，可在其中粘贴打开"
                            else -> "已打开应用，内容已复制"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(primaryLabel)
            }
            Spacer(Modifier.height(8.dp))

            // 微信/支付宝专用码：说明为什么必须进对应 App，避免用户以为是 App 出了问题
            plan?.let { planHint(it) }?.let { hint ->
                Text(
                    hint,
                    color = Color(0xFF9CA3AF),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }

            // 「也可以交给」：只在主按钮**无法唯一确定目标**时出现。
            // 有专属归属的码（微信码/支付宝码/美团码）不显示 —— 那是画蛇添足。
            val altApps = remember(content) {
                altAppsForContent(content.raw, content.type) { QrActions.isInstalled(context, it) }
            }
            if (altApps.isNotEmpty()) {
                Text(
                    "也可以交给",
                    color = Color(0xFF9CA3AF),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    altApps.forEach { alt ->
                        OutlinedButton(
                            onClick = {
                                val r = QrActions.openAlt(context, content, alt)
                                handleOpenResult(context, r)
                                if (r == OpenResult.OK && alt == AltApp.WECHAT) {
                                    // URL 在微信内置浏览器中直接打开了
                                    Toast.makeText(context, "正在用微信打开", Toast.LENGTH_SHORT).show()
                                } else if (r == OpenResult.APP_OPENED) {
                                    val msg = if (alt == AltApp.WECHAT) {
                                        "内容已复制，请在微信里点「扫一扫」"
                                    } else {
                                        "已打开${alt.label()}，内容已交给它处理"
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(alt.label(), color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // 链接类：应用内浏览器选择器（绕开国产 ROM 对系统选择器的限制）
            if (content.type == QrType.URL && plan !is LinkPlan.WeChatCode && browsers.isNotEmpty()) {
                OutlinedButton(
                    onClick = { showBrowserPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("选择浏览器打开", color = Color.White)
                }
                Spacer(Modifier.height(8.dp))
            }

            // 微信专属内容：不提供「选择其他应用打开」——
            // 系统选择器里那两条同名「微信」都是死路（GetQRCodeInfoUI 不可导出 +
            // WXCustomSchemeEntryActivity 是空壳），点了只会"什么都不发生"。
            // 与其把用户送进死路，不如说清楚为什么。
            // 另：候选项为空（纯文本 / Wi-Fi）时也不显示，那种内容本来就没有外部打开方式。
            val weChatOnly = plan is LinkPlan.WeChatCode
            if (!weChatOnly && chooserApps.isNotEmpty()) {
                OutlinedButton(
                    onClick = { showAppPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("选择其他应用打开", color = Color.White)
                }
                Spacer(Modifier.height(6.dp))
            }

            // 直达浏览器入口：告诉用户当前用的哪个浏览器，并可直接改
            if (content.type == QrType.URL && plan !is LinkPlan.WeChatCode) {
                androidx.compose.material3.TextButton(onClick = onOpenSettings) {
                    Text(
                        "默认浏览器：" + (browserName ?: "每次询问") + " ›  点此更改",
                        color = Color(0xFF9CA3AF),
                        fontSize = 13.sp
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        OutlinedButton(
            onClick = {
                clipboard.setText(AnnotatedString(content.copyText))
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (content.wifi != null) "复制信息" else "复制",
                color = Color.White
            )
        }
        Spacer(Modifier.height(10.dp))

        OutlinedButton(
            onClick = { QrActions.share(context, content) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("分享", color = Color.White)
        }
        Spacer(Modifier.height(10.dp))

        Button(
            onClick = onRescan,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A))
        ) {
            Text("重新扫描", color = Color.White)
        }
    }

    // 应用内浏览器选择器：用 setPackage 直启，绕开系统选择器被 ROM 阉割的问题
    if (showBrowserPicker) {
        AlertDialog(
            onDismissRequest = { showBrowserPicker = false },
            containerColor = Color(0xFF1C1C1C),
            title = { Text("选择浏览器打开", color = Color.White, fontSize = 17.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    browsers.forEach { b ->
                        Text(
                            b.label,
                            color = Color(0xFFE5E7EB),
                            fontSize = 16.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showBrowserPicker = false
                                    handleOpenResult(
                                        context,
                                        QrActions.openInBrowser(context, b.packageName, content.raw)
                                    )
                                }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBrowserPicker = false }) {
                    Text("取消", color = Color(0xFF9CA3AF))
                }
            }
        )
    }

    // 应用内「选择其他应用打开」：不用系统选择器，理由见上面 chooserApps 的注释
    if (showAppPicker) {
        AlertDialog(
            onDismissRequest = { showAppPicker = false },
            containerColor = Color(0xFF1C1C1C),
            title = { Text(pickerTitle, color = Color.White, fontSize = 17.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    chooserApps.forEach { app ->
                        Text(
                            app.label,
                            color = Color(0xFFE5E7EB),
                            fontSize = 16.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showAppPicker = false
                                    handleOpenResult(
                                        context,
                                        QrActions.openWithPackage(context, content, app.packageName)
                                    )
                                }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAppPicker = false }) {
                    Text("取消", color = Color(0xFF9CA3AF))
                }
            }
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text("$label：", color = Color(0xFF9CA3AF), fontSize = 14.sp)
        SelectionContainer {
            Text(value, color = Color(0xFFE5E7EB), fontSize = 14.sp)
        }
    }
}

/** 统一处理外跳结果提示（APP_OPENED 由调用方给更具体的说明） */
private fun handleOpenResult(context: android.content.Context, result: OpenResult) {
    val msg = when (result) {
        OpenResult.OK, OpenResult.APP_OPENED -> null
        OpenResult.NO_HANDLER -> "当前设备没有可以打开此内容的应用"
        OpenResult.NOT_SUPPORTED -> "无法安全处理此内容"
        OpenResult.FAILED -> "打开失败"
    }
    msg?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
}
