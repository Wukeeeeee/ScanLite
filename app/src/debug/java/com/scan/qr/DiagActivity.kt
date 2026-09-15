package com.scan.qr

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import com.scan.qr.scanner.BrowserPref
import com.scan.qr.scanner.PKG_WECHAT
import com.scan.qr.scanner.QrActions
import com.scan.qr.scanner.parseQrContent
import com.scan.qr.scanner.planForUrl
import com.scan.qr.scanner.planLabel

/**
 * 真机取证页（仅 debug 变体）。
 *
 * 为什么需要它：`adb shell am start -p <pkg>` 是 **shell 身份**，ColorOS 会用「后台启动 Activity」
 * 策略拦掉对第三方 App 的隐式启动（报错是 "unable to resolve Intent"，极具误导性），
 * 所以 adb 观察不到应用内的真实行为。这个页面跑在应用自己的进程/UID 里且有前台界面，
 * 等价于用户点按钮时的上下文，结果同时打到 logcat（tag = QRDIAG）和屏幕上。
 */
class DiagActivity : Activity() {

    private val sb = StringBuilder()

    private fun log(line: String) {
        Log.i(TAG, line)
        sb.append(line).append('\n')
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            textSize = 10f
            setPadding(24, 24, 24, 24)
        }
        setContentView(ScrollView(this).apply {
            addView(tv, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })

        val pm = packageManager
        log("== env ==")
        log("self=${packageName} vc=${runCatching { pm.getPackageInfo(packageName, 0).longVersionCode }.getOrNull()}")

        log("== 1. getLaunchIntentForPackage ==")
        for (pkg in TARGETS) {
            val i = runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrElse { "EX:$it" }
            log("  $pkg -> $i")
        }

        log("== 2. query MAIN+LAUNCHER (global) ==")
        val mainLauncher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val all = runCatching { pm.queryIntentActivities(mainLauncher, 0) }.getOrElse { emptyList() }
        log("  total=${all.size}")
        for (pkg in TARGETS) {
            val hits = all.filter { it.activityInfo.packageName == pkg }
                .map { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
            log("  $pkg -> $hits")
        }

        log("== 3. query MAIN+LAUNCHER setPackage ==")
        for (pkg in TARGETS) {
            val n = runCatching {
                pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg), 0
                ).size
            }.getOrElse { "EX:$it" }
            log("  $pkg -> $n")
        }

        log("== 4. http / https 处理者 ==")
        for (scheme in listOf("http", "https")) {
            val list = runCatching {
                pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://example.com")), 0)
            }.getOrElse { emptyList() }
            log("  [$scheme] n=${list.size}")
            list.forEach { log("      ${it.activityInfo.packageName}/${it.activityInfo.name}") }
        }

        log("== 5. fakeWebComponentNames ==")
        val fake = runCatching { BrowserPref.fakeWebComponentNames(this) }.getOrElse { listOf("EX:$it") }
        log("  $fake")

        log("== 6. listBrowsers ==")
        runCatching { BrowserPref.listBrowsers(this) }.getOrElse { emptyList() }
            .forEach { log("  ${it.packageName} / ${it.label}") }

        log("== 7. planForUrl ==")
        for (url in listOf(
            "https://u.wechat.com/MMLB2g6DkAr2Vr1mja7WoA?s=4",
            "https://dpurl.cn/abc123",
            "https://qr.alipay.com/abc"
        )) {
            val plan = planForUrl(url, { QrActions.isInstalled(this, it) }, BrowserPref.preferred(this))
            log("  $url -> ${plan.javaClass.simpleName} :: ${planLabel(plan)} :: $plan")
        }

        // 先把已采集的结论刷到屏幕上，后面几步会抢占前台
        tv.text = sb.toString()

        // ---- 以下可选（用 --ez 控制），因为会抢占前台 ----
        val doLaunch = intent?.getBooleanExtra("launch", false) == true
        val doChooser = intent?.getBooleanExtra("chooser", false) == true

        log("== 8. launchApp(wechat) [launch=$doLaunch] ==")
        if (doLaunch) {
            val r = runCatching { QrActions.launchApp(this, PKG_WECHAT) }.getOrElse { "EX:$it" }
            log("  result=$r")
        }

        log("== 10. explicit ComponentName [launch=$doLaunch] ==")
        if (doLaunch) {
            val explicit = runCatching {
                val cmp = pm.queryIntentActivities(mainLauncher, 0)
                    .firstOrNull { it.activityInfo.packageName == PKG_WECHAT }
                    ?.let { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
                if (cmp == null) "no-launcher-component-found"
                else {
                    startActivity(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cmp)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    "started $cmp"
                }
            }.getOrElse { "EX:$it" }
            log("  $explicit")
        }

        // 放最后：选择器要停在最上层，才能截图看清列表里有没有「微信」
        log("== 9. 系统选择器 for WeChat URL [chooser=$doChooser] ==")
        if (doChooser) {
            val c = runCatching {
                QrActions.openWithChooser(this, parseQrContent(WECHAT_URL))
            }.getOrElse { "EX:$it" }
            log("  chooserResult=$c")
        }
    }

    private companion object {
        const val TAG = "QRDIAG"
        const val WECHAT_URL = "https://u.wechat.com/MMLB2g6DkAr2Vr1mja7WoA?s=4"
        val TARGETS = listOf(
            "com.tencent.mm",
            "com.eg.android.AlipayGphone",
            "com.sankuai.meituan",
            "com.taobao.taobao"
        )
    }
}
