package com.scan.qr.scanner

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** 一个可选的浏览器/应用 */
data class BrowserChoice(val packageName: String, val label: String)

/**
 * 已知浏览器清单（包名 → 兜底显示名）。
 *
 * 为什么必须内置这份清单：部分国产 ROM 在 PackageManager 层把 http/https 的
 * 处理者查询限制给了自家浏览器 —— `queryIntentActivities(ACTION_VIEW, https://…)`
 * 只返回系统浏览器，Edge / Chrome 明明装了也查不到（ColorOS 16 真机实测）。
 * 但 `setPackage` 直启这些浏览器仍然成功，所以列表必须自带候选，再用「已安装」过滤。
 */
val KNOWN_BROWSERS: List<Pair<String, String>> = listOf(
    "com.microsoft.emmx" to "Edge",
    "com.android.chrome" to "Chrome",
    "com.quark.browser" to "夸克",
    "com.UCMobile" to "UC 浏览器",
    "com.tencent.mtt" to "QQ 浏览器",
    "org.mozilla.firefox" to "Firefox",
    "com.heytap.browser" to "系统浏览器",
    "com.android.browser" to "浏览器",
    "com.baidu.searchbox" to "百度",
    "com.sec.android.app.sbrowser" to "三星浏览器",
    "com.opera.browser" to "Opera",
    "mark.via" to "Via",
    "com.vivo.browser" to "vivo 浏览器",
    "com.miui.browser" to "小米浏览器"
)

/**
 * 合并「系统查询到的」+「已知清单里已安装的」：排除自己、按包名去重、按名称排序。
 * 系统查询结果优先（它的显示名更准）。纯函数，便于单测。
 */
fun mergeBrowsers(
    resolved: List<BrowserChoice>,
    known: List<BrowserChoice>,
    selfPackage: String
): List<BrowserChoice> =
    (resolved + known)
        .filter { it.packageName.isNotBlank() && it.packageName != selfPackage }
        .distinctBy { it.packageName }
        .sortedBy { it.label }

/** 能响应某个 Intent 的一个组件 */
data class HandlerRef(val packageName: String, val component: String)

/**
 * 找出「声明能打开 http 网页、实际打不开」的**假处理者**，用于从系统选择器里剔除。
 *
 * 真机实测（一加 Ace 3 / ColorOS 16 / Android 16 / 最新微信）：
 * 微信注册的是**通配 `http` scheme**（不含 https），所以任何 http 链接的「打开方式」
 * 列表里都会出现「微信」；但它的入口是 WXCustomSchemeEntryActivity 空壳，
 * 点下去什么界面都不会出现 —— 用户看到的就是「点了微信，跳转不了」。
 * 这不是本 App 的问题，但可以替用户挡掉。
 *
 * 规则：能 http、不能 https、且不在已知浏览器清单里 → 假处理者。纯函数，便于单测。
 */
fun fakeWebComponents(
    httpHandlers: List<HandlerRef>,
    httpsHandlers: List<HandlerRef>,
    knownBrowserPackages: Set<String>
): List<String> {
    val httpsPkgs = httpsHandlers.mapTo(HashSet()) { it.packageName }
    return httpHandlers
        .filter { it.packageName !in httpsPkgs && it.packageName !in knownBrowserPackages }
        .map { it.component }
        .distinct()
}

/**
 * "默认浏览器"偏好。
 *
 * 为什么需要它：Android 的系统选择器每次都弹很烦，而"设为默认浏览器"又只能在
 * 系统「默认应用」里改（用户找不到）。这里在 App 内自己记一个选择，
 * 扫到网址就直接用它打开 —— 既"静默"，又能指定 Edge 而不是原生浏览器。
 */
object BrowserPref {
    private const val PREF = "qr_open_pref"
    private const val KEY_PKG = "default_browser_pkg"
    private const val KEY_LABEL = "default_browser_label"

    /** 已设的默认浏览器包名；未设置返回 null（表示"每次询问"） */
    fun preferred(context: Context): String? =
        prefs(context).getString(KEY_PKG, null)?.takeIf { it.isNotBlank() }

    fun preferredLabel(context: Context): String? =
        prefs(context).getString(KEY_LABEL, null)?.takeIf { it.isNotBlank() }

    /** 取消偏好且清掉残留的显示名 */
    fun setPreferred(context: Context, packageName: String, label: String) {
        prefs(context).edit()
            .putString(KEY_PKG, packageName)
            .putString(KEY_LABEL, label)
            .apply()
    }

    /** 清除 → 回到"每次询问" */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_PKG).remove(KEY_LABEL).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /**
     * 枚举能打开网页的应用（浏览器等）：
     * 「系统查询结果」∪「内置已知清单中已安装的」→ 去重、排除本 App、按名称排序。
     * 内置清单是为了绕开国产 ROM 对 PackageManager 查询的隐藏。
     * 另外剔除「假处理者」（微信这种通配 http 却打不开网页的），避免用户点了没反应。
     */
    fun listBrowsers(context: Context): List<BrowserChoice> {
        val pm = context.packageManager
        val fake = fakePackages(context)

        val resolved = try {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), 0
            ).map {
                BrowserChoice(it.activityInfo.packageName, it.loadLabel(pm).toString())
            }.filter { it.packageName !in fake }
        } catch (e: Exception) {
            emptyList()
        }

        val known = KNOWN_BROWSERS.mapNotNull { (pkg, fallback) ->
            installedLabel(pm, pkg)?.let { BrowserChoice(pkg, it.ifBlank { fallback }) }
        }

        return mergeBrowsers(resolved, known, context.packageName)
    }

    /** 某个 scheme 下系统能解析出的全部组件 */
    private fun webHandlers(context: Context, scheme: String): List<HandlerRef> = try {
        context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://example.com")), 0
        ).map {
            val ai = it.activityInfo
            HandlerRef(ai.packageName, "${ai.packageName}/${ai.name}")
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** 要踢出选择器的「假处理者」包名（微信这类） */
    fun fakePackages(context: Context): Set<String> {
        val http = webHandlers(context, "http")
        val https = webHandlers(context, "https")
        val knownPkgs = KNOWN_BROWSERS.mapTo(HashSet()) { it.first }
        val httpsPkgs = https.mapTo(HashSet()) { it.packageName }
        return http.filter { it.packageName !in httpsPkgs && it.packageName !in knownPkgs }
            .mapTo(HashSet()) { it.packageName }
    }

    /** 要传给 `Intent.EXTRA_EXCLUDE_COMPONENTS` 的组件名列表 */
    fun fakeWebComponentNames(context: Context): List<String> =
        fakeWebComponents(
            httpHandlers = webHandlers(context, "http"),
            httpsHandlers = webHandlers(context, "https"),
            knownBrowserPackages = KNOWN_BROWSERS.mapTo(HashSet()) { it.first }
        )

    /** 已安装则返回显示名（拿不到就返回兜底名），未安装返回 null */
    private fun installedLabel(pm: PackageManager, pkg: String): String? = try {
        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
    } catch (e: Exception) {
        null
    }
}
