package com.scan.qr.scanner

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

enum class OpenResult {
    OK,
    /** App 被拉起来了，但没能直达具体页面（内容已复制，界面会提示用户） */
    APP_OPENED,
    NO_HANDLER,
    NOT_SUPPORTED,
    FAILED
}

/**
 * 非链接类内容的打开策略（纯逻辑，可单测）
 *
 * - DIRECT：只解析到一个处理应用 → 直接交给它
 * - CHOOSER：解析到多个 → 让用户挑（系统选择器）
 * - SHOW_ONLY：**一个都没有** → 什么都不打开，把原始内容留在结果页
 *   （需求：无法处理时不要随便丢给浏览器，页面上已经有内容和复制按钮了）
 */
enum class OpenStrategy { DIRECT, CHOOSER, SHOW_ONLY }

/**
 * 决定如何打开非链接类内容（Deep Link / 电话 / 短信 / 邮件 / 地图）：
 * - Deep Link（weixin://、alipays:// 等）：只有 1 个处理应用就直接跳转
 * - 解析到 >1 个：交给系统选择器
 * - 解析到 0 个：**不打开任何东西**（页面内已有原始内容，不丢浏览器）
 *
 * 链接类（http/https）不走这里 —— 见 [planForUrl]。
 */
fun decideStrategy(type: QrType, handlerCount: Int): OpenStrategy = when {
    type == QrType.URL -> OpenStrategy.CHOOSER
    handlerCount == 1 -> OpenStrategy.DIRECT
    handlerCount > 1 -> OpenStrategy.CHOOSER
    else -> OpenStrategy.SHOW_ONLY
}

/** 常见 App 的 scheme → 中文名，仅用于按钮文案提示，不参与拉起逻辑、不写死包名 */
private val KNOWN_SCHEMES = mapOf(
    "weixin" to "微信",
    "wxwork" to "企业微信",
    "alipay" to "支付宝",
    "alipays" to "支付宝",
    "alipayqr" to "支付宝",
    "meituanbike" to "美团单车",
    "mobike" to "美团单车",
    "taobao" to "淘宝",
    "tencent" to "QQ",
    "mqq" to "QQ",
    "dingtalk" to "钉钉",
    "upwallet" to "云闪付",
    "snssdk1128" to "抖音",
    "sinaweibo" to "微博",
    "bilibili" to "哔哩哔哩",
    "zhihu" to "知乎",
    "xhsdiscover" to "小红书",
    "openapp.jdmobile" to "京东",
    "pinduoduo" to "拼多多"
)

/** 从 raw 里取出 scheme 对应的 App 名；未知返回 null */
fun appNameForContent(raw: String): String? {
    val scheme = raw.substringBefore(':').lowercase().takeIf { it.isNotBlank() } ?: return null
    return KNOWN_SCHEMES[scheme]
}

/**
 * 已知归属的 scheme → 包名。用途：同一个 scheme 可能被多个 App 声明，
 * 这时系统选择器/系统默认都会被抢；我们**只按公开事实**定向到真正的归属方
 * （例如 `alipayqr://` 就是支付宝的）。
 * ⚠️ 只写确实注册了该 scheme 的 App，不猜测；查不到就退回正常解析。
 */
private val KNOWN_SCHEME_PACKAGES: Map<String, String> = mapOf(
    "alipay" to PKG_ALIPAY,
    "alipays" to PKG_ALIPAY,
    "alipayqr" to PKG_ALIPAY,
    "taobao" to "com.taobao.taobao",
    "openapp.jdmobile" to "com.jingdong.app.mall",
    "pinduoduo" to "com.xunmeng.pinduoduo",
    "snssdk1128" to "com.ss.android.ugc.aweme",
    "xhsdiscover" to "com.xingin.xhs",
    "bilibili" to "tv.danmaku.bili",
    "sinaweibo" to "com.sina.weibo",
    "mqq" to "com.tencent.mobileqq",
    "dingtalk" to "com.alibaba.android.rimet"
)

// ---------------------------------------------------------------------------
// 链接类（http/https）的打开计划
// ---------------------------------------------------------------------------

const val PKG_WECHAT = "com.tencent.mm"
const val PKG_ALIPAY = "com.eg.android.AlipayGphone"

/**
 * 微信专用码：真机（一加 Ace 3 / ColorOS 16 / Android 16 / 最新微信）逐条实测的结论 ——
 * 第三方 App **没有任何一条可用路径**：
 *   - `https://u.wechat.com/<码>`（好友码真实形态）→ 系统里 0 个 App 处理者，只会进浏览器
 *   - `http(s)://weixin.qq.com/r/<码>` → 系统里唯一的处理者是
 *     `com.tencent.mm.plugin.setting.ui.qrcode.GetQRCodeInfoUI`，而它的
 *     `android:exported=false`：`am start` 实测直接抛
 *     `SecurityException: ... not exported from uid 10441`。第三方永不可达。
 *   - `weixin://qr/<码>` → 系统能解析出**两个都叫「微信」的过滤器**：
 *     不可导出的 GetQRCodeInfoUI + 空壳 `WXCustomSchemeEntryActivity`。
 *     丢进系统选择器 = 两条同名「微信」，点中哪条都「什么都不发生」。
 *     ⚠️ 这正是用户反馈「弹了选项、点了微信、跳转不了」的成因。
 *   - `weixin://dl/scan` → 微信内 WebView「对不起，当前页面无法访问」
 *   - `weixin://scanqrcode` → 落到同一个空壳
 *
 * 结论：不要再假装能唤起扫一扫，也**不要把它丢进系统选择器**。
 * 唯一诚实且可用的做法是「复制内容 + 拉起微信 App + 屏内引导用扫一扫」。
 *
 * 技术细节（`GetQRCodeInfoUI exported=false` 之类）只留在代码注释里，**不要写进给用户看的文案**
 * —— 用户要的是「我接下来该点哪」，不是包名和 exported 标志。
 */

/**
 * 支付宝支持把二维码内容直接交给它处理：
 * alipayqr://platformapi/startapp?saId=10000007&qrcode=<二维码内容>
 * 这正是「扫一扫」入口，支付宝会自己解析这个码。
 */
internal fun alipayChain(raw: String): List<String> {
    val encoded = try {
        URLEncoder.encode(raw, "UTF-8")
    } catch (e: Exception) {
        raw
    }
    return listOf(
        "alipayqr://platformapi/startapp?saId=10000007&qrcode=$encoded",
        "alipayqr://platformapi/startapp?saId=10000007",
        "alipay://"
    )
}

/** 微信专用域名：只能微信打开 */
private val WECHAT_HOSTS = listOf(
    "u.wechat.com", "weixin.qq.com", "mp.weixin.qq.com", "weixin110.qq.com", "w.url.cn"
)

/** 微信系 scheme。注意：这两条下的过滤器都是死路，只用于识别，不用于直达 */
private val WECHAT_SCHEMES = setOf("weixin", "wechat")

/**
 * 曾经用过、**现在刻意不用**的入口：`weixin://scanqrcode`（微信自己公开注册的扫一扫 scheme）。
 *
 * 不用它的原因：微信为这个 scheme 注册了两个 Activity —— 真正的二维码入口（不导出）和空壳
 * `com.tencent.mm/.plugin.base.stub.WXCustomSchemeEntryActivity`。隐式 startActivity 命中
 * 多个 handler 时系统会弹消歧框，而真机实测它一个界面都不出，属于纯噪声。
 * 现在只剩一条路：复制内容 + [QrActions.launchApp] 显式拉起微信主界面。
 *
 * ⚠️ 顺带记一个**系统级**事实（不是本 App 的 bug）：开了「应用分身」的 ColorOS 上，
 * 第三方 App 任何一次拉起微信都会被 `com.oplus.multiapp/.chooser.MultiAppResolverActivity`
 * 拦一道，让用户选「微信 / Wechat(分身)」。这是 ROM 策略，第三方无法也不应绕过。
 */

/** 支付宝专用域名：可以用 alipayqr scheme 交给支付宝 */
private val ALIPAY_HOSTS = listOf(
    "qr.alipay.com", "render.alipay.com", "m.alipay.com", "alipay.com", "alipayobjects.com"
)

/**
 * 应用专属链接的目标 App。
 *
 * @param hosts 该 App 的域名（含短链域名）。**短链域名最容易漏**：新浪有 `t.cn`、B 站有
 *   `b23.tv`、美团/点评有 `dpurl.cn`（备案主体上海汉涛 = 大众点评，只服务美团自己的业务）。
 *   街上扫到的「美团码」多半就是 `dpurl.cn/xxxx` —— 漏了它，整条链路就直接掉到浏览器。
 * @param schemeFallbacks 该 App 的官方 scheme 模板，按顺序尝试，任一成功即止。占位符：
 *   `{raw}` = 二维码原文，`{rest}` = 去掉 `http(s)://` 的原文，`{url}` = URL 编码后的原文。
 *   ⚠️ 只写**有公开资料佐证**的 scheme。解析得到但点了没反应的 scheme（典型例子：
 *   微信的 `WXCustomSchemeEntryActivity`）比跳浏览器更让人恼火，不要往里塞猜测的写法。
 */
data class AppTarget(
    val packageName: String,
    val appName: String,
    val hosts: List<String>,
    val schemeFallbacks: List<String> = emptyList()
)

/**
 * 域名 → App 表。要拓展新 App，在这里加一行即可（hosts + 可选 scheme 模板），
 * 并在 AndroidManifest 的 `<queries>` 里补上包名，否则 Android 11+ 查不到该 App。
 */
private val APP_TARGETS: List<AppTarget> = listOf(
    AppTarget(
        packageName = "com.taobao.taobao",
        appName = "淘宝",
        hosts = listOf("taobao.com", "tb.cn", "tmall.com", "m.tb.cn", "e.tb.cn"),
        // 公开写法：taobao:// + 原链接去掉 scheme 的部分，可直达商品/店铺页
        schemeFallbacks = listOf("taobao://{rest}")
    ),
    AppTarget(
        packageName = "com.jingdong.app.mall",
        appName = "京东",
        hosts = listOf("jd.com", "3.cn", "jd.hk"),
        schemeFallbacks = listOf("openapp.jdMobile://")
    ),
    AppTarget(
        packageName = "com.xunmeng.pinduoduo",
        appName = "拼多多",
        hosts = listOf("pinduoduo.com", "yangkeduo.com"),
        // 公开写法：scheme://包名/<完整 https 链接>
        schemeFallbacks = listOf("pinduoduo://com.xunmeng.pinduoduo/{raw}")
    ),
    AppTarget(
        packageName = "com.sankuai.meituan",
        appName = "美团",
        // dpurl.cn = 美团/点评自家短链；i.meituan.com、waimai.meituan.com 等子域由后缀匹配覆盖
        hosts = listOf(
            "meituan.com", "meituan.net", "sankuai.com",
            "dpurl.cn", "dianping.com", "dianping.net"
        )
    ),
    AppTarget(
        packageName = "com.ss.android.ugc.aweme",
        appName = "抖音",
        hosts = listOf("douyin.com", "iesdouyin.com", "v.douyin.com")
    ),
    AppTarget(
        packageName = "com.xingin.xhs",
        appName = "小红书",
        hosts = listOf("xiaohongshu.com", "xhslink.com")
    ),
    AppTarget(
        packageName = "tv.danmaku.bili",
        appName = "哔哩哔哩",
        hosts = listOf("bilibili.com", "b23.tv")
    ),
    AppTarget(
        packageName = "com.sina.weibo",
        appName = "微博",
        hosts = listOf("weibo.com", "weibo.cn")
    )
)

/** 展开 scheme 模板中的占位符 */
fun expandSchemeTemplate(template: String, raw: String): String {
    val rest = raw.substringAfter("://", raw)
    val encoded = try {
        URLEncoder.encode(raw, "UTF-8")
    } catch (e: Exception) {
        raw
    }
    return template
        .replace("{rest}", rest)
        .replace("{url}", encoded)
        .replace("{raw}", raw)
}

/**
 * 组件引用。**不用 android.content.ComponentName**：那是未 mock 的 Android 类，
 * 在纯 JVM 单测里会抛 RuntimeException，导致兜底逻辑没法测。纯 Kotlin 结构体最省事。
 */
data class ComponentRef(val packageName: String, val className: String)

/** 从查询结果里挑出目标包的启动组件（纯函数，便于单测） */
fun pickLauncherComponent(candidates: List<ComponentRef>, packageName: String): ComponentRef? =
    candidates.firstOrNull { it.packageName == packageName }

/** 链接的最终打开计划 */
sealed interface LinkPlan {
    /**
     * 直达某个 App：App Links（指定包名直达）→ 该 App 的官方 scheme → 至少把 App 打开。
     * [fallbackUris] 为空表示该 App 只有 App Links 一条直连路径。
     */
    data class AppLink(
        val pkg: String,
        val appName: String,
        val label: String,
        val fallbackUris: List<String> = emptyList()
    ) : LinkPlan

    /** 微信专用码：第三方无法直达（微信已关闭全部唤起通道），只能复制内容 + 拉起微信 */
    data class WeChatCode(val pkg: String) : LinkPlan

    /** 用 App 专用 scheme 直达（支付宝 qrcode 接口）；uris 为回退链 */
    data class Scheme(
        val uris: List<String>,
        val appName: String,
        val label: String
    ) : LinkPlan

    /** 用 App 内设定的默认浏览器（用户在设置页明确选过） */
    data class Browser(val pkg: String) : LinkPlan

    /**
     * 普通 http/https 链接：**用系统标准 URL Intent 直接打开，不指定任何包名**，
     * 由系统自己的「默认浏览器」决定，没有默认就由系统弹出让用户选。
     * 这是「不要强制指定 Edge/Chrome」的落点（vs 上面的 [Browser] 是用户主动指定过的）。
     */
    data object SystemDefault : LinkPlan

    /** 系统选择器（仅用于用户主动点「选择其他应用打开」） */
    data object Chooser : LinkPlan
}

/** 应用内选择器里的一个候选项：能打开当前内容的一个 App */
data class AppChoice(val packageName: String, val label: String)

/**
 * 合并选择器候选：系统查询到的 ∪ 已安装的已知浏览器（仅链接类需要，见 [KNOWN_BROWSERS]）。
 * 系统结果优先（显示名更准），排除自己、按包名去重、按名称排序。纯函数，便于单测。
 */
fun mergeChooserApps(
    resolved: List<AppChoice>,
    browsers: List<AppChoice>,
    isUrl: Boolean,
    selfPackage: String
): List<AppChoice> =
    (resolved + if (isUrl) browsers else emptyList())
        .filter { it.packageName.isNotBlank() && it.packageName != selfPackage }
        .distinctBy { it.packageName }
        .sortedBy { it.label }

/** 取 http(s) 链接的 host（小写、去 www.）；非 http(s) 返回 null */
fun urlHost(raw: String): String? {
    val lower = raw.trim().lowercase()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
    val host = lower.substringAfter("://", "")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringBefore(':')
        .removePrefix("www.")
    return host.takeIf { it.isNotBlank() }
}

private fun hostMatches(host: String, domains: List<String>): Boolean =
    domains.any { d -> host == d || host.endsWith(".$d") }

/**
 * 链接类的最终计划（纯函数，`installed` 注入以便单测）：
 * 1. 微信专用域名且微信已装 → 用微信 scheme 唤起（唯一能打开的方式）
 * 2. 支付宝专用域名且支付宝已装 → 用 alipayqr 把码交给支付宝
 * 3. 其它 App 域名且对应 App 已装 → App Links 直达
 * 4. 设了默认浏览器 → 用它
 * 5. 都没有 → 系统选择器
 */
fun planForUrl(
    raw: String,
    installed: (String) -> Boolean,
    preferredBrowser: String?
): LinkPlan {
    val host = urlHost(raw)
    if (host == null) return fallbackPlan(preferredBrowser)

    if (hostMatches(host, WECHAT_HOSTS) && installed(PKG_WECHAT)) {
        return LinkPlan.WeChatCode(PKG_WECHAT)
    }
    if (hostMatches(host, ALIPAY_HOSTS) && installed(PKG_ALIPAY)) {
        return LinkPlan.Scheme(
            uris = alipayChain(raw),
            appName = "支付宝",
            label = "用支付宝打开"
        )
    }
    APP_TARGETS.firstOrNull { t -> hostMatches(host, t.hosts) }?.let { t ->
        if (installed(t.packageName)) {
            return LinkPlan.AppLink(
                pkg = t.packageName,
                appName = t.appName,
                label = "用${t.appName}打开",
                fallbackUris = t.schemeFallbacks.map { expandSchemeTemplate(it, raw) }
            )
        }
    }
    return fallbackPlan(preferredBrowser)
}

/**
 * 内容是不是「只有微信能处理」的那一类：
 * 1. 微信域名链接（u.wechat.com / weixin.qq.com / mp.weixin.qq.com / w.url.cn …）
 * 2. `weixin://` / `wechat://` 系 scheme（QrContent 会把它归为 QrType.APP）
 *
 * 这两类都必须走「复制 + 拉起微信」，**绝不能**丢进系统选择器 ——
 * 选择器里会出现两条同名「微信」，都是死路 —— 所以这类内容整条绕开选择器。
 */
fun isWeChatOnly(raw: String, type: QrType): Boolean = when (type) {
    QrType.URL -> urlHost(raw)?.let { hostMatches(it, WECHAT_HOSTS) } == true
    QrType.APP -> raw.trim().substringBefore(':').lowercase() in WECHAT_SCHEMES
    else -> false
}

/** scheme 是不是微信系（与类型无关，供上层做文案判断） */
fun isWeChatScheme(raw: String): Boolean =
    raw.trim().substringBefore(':').lowercase() in WECHAT_SCHEMES

/** 内容的打开计划（链接类 + 微信 scheme 类；其它类型返回 null） */
fun planForContent(
    raw: String,
    type: QrType,
    installed: (String) -> Boolean,
    preferredBrowser: String?
): LinkPlan? = when {
    type == QrType.URL -> planForUrl(raw, installed, preferredBrowser)
    type == QrType.APP && isWeChatScheme(raw) && installed(PKG_WECHAT) ->
        LinkPlan.WeChatCode(PKG_WECHAT)

    else -> null
}

/**
 * 分流用的粗分类 —— 「识别结果 → 下一步交给谁」的第一道判断（纯函数，可单测）。
 *
 * 与 [QrType] 的分工：`QrType` 回答"这串内容**是什么**"（解析层，在 `parseQrContent`），
 * 本枚举回答"该**交给谁**"（分流层）。两者分开，是为了让"类型判断"只有一个落点。
 *
 * - WEB_URL：`http://` / `https://` → 系统默认浏览器（标准 URL Intent，不指定包名）
 * - PLAIN_TEXT：无 scheme 的纯文本 → 不打开，页面内展示 + 复制
 * - WECHAT：微信域名 / `weixin://`·`wechat://` → 复制 + 拉起微信
 *   （微信已把二维码入口设为不导出，第三方无法直达）
 * - APP_DEEP_LINK：其它 App 的自定义 scheme → 先查该 App 能否处理；能→交给它，不能→留页内
 * - SYSTEM_ACTION：tel / sms / mailto / geo → 交给系统对应 App
 * - UNKNOWN：被禁 scheme（javascript: / intent: 等）→ 不打开，仅展示
 */
enum class QrRoutingKind { WEB_URL, PLAIN_TEXT, WECHAT, APP_DEEP_LINK, SYSTEM_ACTION, UNKNOWN }

private val SYSTEM_ACTION_TYPES = setOf(QrType.TEL, QrType.SMS, QrType.EMAIL, QrType.GEO)

fun routingKind(raw: String, type: QrType): QrRoutingKind = when {
    // ⚠️ 顺序要紧：微信专属内容里有一半是 URL（u.wechat.com / weixin.qq.com），
    // 必须先判微信，否则会被当成普通网页丢给浏览器。
    isWeChatOnly(raw, type) -> QrRoutingKind.WECHAT
    type == QrType.URL -> QrRoutingKind.WEB_URL
    type in SYSTEM_ACTION_TYPES -> QrRoutingKind.SYSTEM_ACTION
    type == QrType.APP -> QrRoutingKind.APP_DEEP_LINK
    type == QrType.TEXT -> QrRoutingKind.PLAIN_TEXT
    else -> QrRoutingKind.UNKNOWN
}

/** 分类的中文名 */
fun QrRoutingKind.label(): String = when (this) {
    QrRoutingKind.WEB_URL -> "网页链接"
    QrRoutingKind.PLAIN_TEXT -> "普通文本"
    QrRoutingKind.WECHAT -> "微信专用内容"
    QrRoutingKind.APP_DEEP_LINK -> "其他 App 的链接"
    QrRoutingKind.SYSTEM_ACTION -> "系统操作"
    QrRoutingKind.UNKNOWN -> "未知类型"
}

/**
 * 结果页顶部那个分类标签。
 *
 * 默认用 [QrType] 的名字（更具体，如「电话」「Wi-Fi」），但**微信专属内容必须特殊处理**：
 * 微信好友码的 `QrType` 是 `URL`，直接取 `type.label` 会显示成「网页链接」，
 * 和下面的「复制并打开微信」自相矛盾 —— 用户看到的就是"这到底算啥"。
 */
fun categoryLabel(content: QrContent): String =
    if (routingKind(content.raw, content.type) == QrRoutingKind.WECHAT) {
        QrRoutingKind.WECHAT.label()
    } else {
        content.type.label
    }

/** 已知归属的 scheme → 包名（只按公开事实；查不到返回 null） */
fun claimedPackage(raw: String): String? {
    val scheme = raw.trim().substringBefore(':').lowercase()
    return KNOWN_SCHEME_PACKAGES[scheme]
}

/** 「也可以交给」的候选 App */
enum class AltApp { ALIPAY, WECHAT }

/**
 * 「也可以交给」的候选。
 *
 * 为什么需要它：**有些码没有任何 App 声明专属归属** —— 聚合付款码、商家码、普通短链都是如此，
 * 丢给支付宝或微信都算合理。这时不该替用户猜，而要把选择权交回去。
 *
 * 反过来，已经能唯一确定归属的（微信专属码 / 支付宝码 / 美团等 App Link）**返回空列表**，
 * 主按钮直达就够了，多画一行只会让人犹豫。
 *
 * 触发条件（都在"主按钮不是唯一解"的前提下）：
 * - 普通 http/https → 主按钮是系统默认浏览器（用户明确要求过，不强制 Edge/Chrome）
 * - 无归属的自定义 scheme → 主按钮只能弹系统选择器
 * - 纯文本 / Wi-Fi / 未知类型 → 永远没有候选
 *
 * 归属判定统一复用 [planForContent]，避免"归属"逻辑出现第二个落点。
 * 这里刻意传 `preferredBrowser = null`：候选与用户设没设默认浏览器无关。
 */
fun altAppsForContent(
    raw: String,
    type: QrType,
    installed: (String) -> Boolean
): List<AltApp> {
    if (type != QrType.URL && type != QrType.APP) return emptyList()
    when (planForContent(raw, type, installed, null)) {
        is LinkPlan.WeChatCode, is LinkPlan.Scheme, is LinkPlan.AppLink -> return emptyList()
        else -> Unit
    }
    // 自定义 scheme 已知归属时，主按钮已经指向那个 App，不必再列一遍
    val claimed = if (type == QrType.APP) claimedPackage(raw) else null
    return buildList {
        if (installed(PKG_ALIPAY) && claimed != PKG_ALIPAY) add(AltApp.ALIPAY)
        if (installed(PKG_WECHAT) && claimed != PKG_WECHAT) add(AltApp.WECHAT)
    }
}

/** 「也可以交给」按钮的文案 */
fun AltApp.label(): String = when (this) {
    AltApp.ALIPAY -> "支付宝"
    AltApp.WECHAT -> "微信"
}

/**
 * 普通链接的归宿：没有用户指定过浏览器时 → [LinkPlan.SystemDefault]
 * （标准 URL Intent，交给系统默认浏览器；**不**指定 Edge/Chrome 之类）。
 */
private fun fallbackPlan(preferredBrowser: String?): LinkPlan =
    if (!preferredBrowser.isNullOrBlank()) LinkPlan.Browser(preferredBrowser)
    else LinkPlan.SystemDefault

/** 主按钮文案 */
fun planLabel(plan: LinkPlan): String = when (plan) {
    is LinkPlan.AppLink -> plan.label
    is LinkPlan.Scheme -> plan.label
    is LinkPlan.WeChatCode -> "复制并打开微信"
    is LinkPlan.Browser -> "用默认浏览器打开"
    LinkPlan.SystemDefault -> "打开链接"
    LinkPlan.Chooser -> "打开网页"
}

/** 结果页给的说明；null 表示不需要额外提示 */
fun planHint(plan: LinkPlan): String? = when (plan) {
    is LinkPlan.WeChatCode ->
        "微信没把「扫一扫」开放给其它 App 调用，所以这个码没法一键跳进去。\n" +
            "点上面按钮会把内容复制好并打开微信。接下来在微信里：\n" +
            "① 点右上角「+」→「扫一扫」，扫眼前这个码；\n" +
            "② 或者把复制的内容粘到「文件传输助手」，再点它打开。"

    is LinkPlan.Scheme ->
        "这个码交给${plan.appName}才能打开，点上面按钮直接跳到${plan.appName}。"

    else -> null
}

/**
 * 所有外跳都走 Android 正规 Intent / PackageManager 机制，且必须由用户主动点击触发。
 * 浏览器不写死：链接一律交给系统选择器或用户自选的默认浏览器。
 */
object QrActions {

    /** 目标包是否已安装 */
    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: Exception) {
        false
    }

    /** 复制纯文本到系统剪贴板（供内部流程用，不依赖 Compose） */
    fun copyToClipboard(context: Context, text: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("qr", text))
        } catch (e: Exception) {
            // 个别 ROM 限制后台写剪贴板：忽略，不影响主流程
        }
    }

    /**
     * 拉起已安装 App 的启动页。**不写死 Activity 名**（微信等入口类名随版本变化，
     * 写死必然失效），三级兜底：
     * 1. `getLaunchIntentForPackage` —— 官方 API，返回值自带显式组件，最可靠
     * 2. `queryIntentActivities(MAIN+LAUNCHER)` 里挑出该包的启动组件 → 显式 ComponentName 启动
     *    （个别 ROM 上 ① 返回 null，但这一条能查到组件，真机验证）
     * 3. `ACTION_MAIN + CATEGORY_LAUNCHER + setPackage` 隐式启动
     *
     * ⚠️ Android 11+ 若没在 `<queries>` 里声明目标包，① 会直接返回 null（不抛异常），
     * 按钮就"点了没反应"。本 App 已在 Manifest 里显式声明全部目标包。
     * ⚠️ 不要用 `adb shell am start -p <pkg>` 验证本函数：shell 没有前台 Activity，
     * 会被 ColorOS 的「后台启动 Activity」策略拦掉，报错是误导性的
     * "unable to resolve Intent"。必须在应用内（有前台界面时）验证。
     */
    fun launchApp(context: Context, packageName: String): OpenResult {
        val pm = context.packageManager

        val direct = try {
            pm.getLaunchIntentForPackage(packageName)
        } catch (e: Exception) {
            null
        }
        if (direct != null && startDirect(context, Intent(direct)) == OpenResult.OK) {
            return OpenResult.APP_OPENED
        }

        val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val component = try {
            pm.queryIntentActivities(probe, 0)
                .mapNotNull { ri ->
                    val ai = ri.activityInfo ?: return@mapNotNull null
                    ComponentRef(ai.packageName, ai.name)
                }
                .let { pickLauncherComponent(it, packageName) }
        } catch (e: Exception) {
            null
        }
        if (component != null &&
            startDirect(
                context,
                Intent(probe).setComponent(ComponentName(component.packageName, component.className))
            ) == OpenResult.OK
        ) {
            return OpenResult.APP_OPENED
        }

        if (startDirect(context, Intent(probe).setPackage(packageName)) == OpenResult.OK) {
            return OpenResult.APP_OPENED
        }
        return OpenResult.NO_HANDLER
    }

    /**
     * 用指定浏览器打开链接（`setPackage` 直启）。
     * 之所以不用系统选择器：国产 ROM（ColorOS 等）在 PackageManager 层隐藏了
     * 非默认浏览器的 http/https 过滤器，系统选择器只会列出系统浏览器；
     * 而 setPackage 直启 Edge / Chrome 是可行的（真机验证）。
     */
    fun openInBrowser(context: Context, packageName: String, raw: String): OpenResult {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(raw)).setPackage(packageName)
        return startDirect(context, intent)
    }

    /**
     * 用微信内置浏览器直接打开 http/https 链接。
     *
     * 微信对 http/https 注册了通配的 `ACTION_VIEW` 过滤器，
     * 对普通 http/https **确实能在内置 WebView 中渲染**（真机验证）。
     *
     * 使用场景：用户在结果页点「也可以交给 → 微信」，直接在微信浏览器中打开链接，
     * 省去"复制 → 手动打开微信 → 扫一扫"的三步。
     *
     * ⚠️ 仅对 http/https 有效；微信专属码（u.wechat.com 等）走 [LinkPlan.WeChatCode]，
     * 不会进这条路径。
     */
    fun openInWeChat(context: Context, raw: String): OpenResult {
        val lower = raw.trim().lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return OpenResult.NOT_SUPPORTED
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(raw)).setPackage(PKG_WECHAT)
        return startDirect(context, intent)
    }

    /** 当前内容的打开计划；没有计划（文本/Wi-Fi 等）返回 null */
    fun linkPlan(context: Context, content: QrContent): LinkPlan? =
        planForContent(
            raw = content.raw,
            type = content.type,
            installed = { isInstalled(context, it) },
            preferredBrowser = BrowserPref.preferred(context)
        )

    /** 该类型是否有“打开/拨打/发送”类主操作 */
    fun hasPrimaryAction(content: QrContent): Boolean = when (content.type) {
        QrType.URL, QrType.TEL, QrType.SMS, QrType.EMAIL, QrType.GEO, QrType.APP, QrType.WIFI -> true
        QrType.TEXT, QrType.UNKNOWN -> false
    }

    /** 非链接类的主按钮文案（链接类请用 planLabel(linkPlan(...))） */
    fun primaryLabel(content: QrContent): String = when (content.type) {
        QrType.URL -> "打开网页"
        QrType.TEL -> "拨打"
        QrType.SMS -> "发送短信"
        QrType.EMAIL -> "发送邮件"
        QrType.GEO -> "打开地图"
        QrType.WIFI -> "连接 Wi-Fi"
        QrType.APP -> appNameForContent(content.raw)?.let { "用${it}打开" } ?: "打开"
        else -> ""
    }

    /** 构造 Intent，未知/不安全类型返回 null */
    fun buildIntent(content: QrContent): Intent? = when (content.type) {
        QrType.URL -> Intent(Intent.ACTION_VIEW, Uri.parse(content.raw))

        QrType.TEL -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(content.display)}"))

        QrType.SMS -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(content.display)}"))
            .apply { content.detail?.let { putExtra("sms_body", it) } }

        QrType.EMAIL -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(content.display)}"))
            .apply {
                content.detail?.lines()?.firstOrNull { it.startsWith("主题：") }
                    ?.removePrefix("主题：")?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            }

        QrType.GEO -> Intent(Intent.ACTION_VIEW, Uri.parse(content.raw))

        QrType.APP -> Intent(Intent.ACTION_VIEW, Uri.parse(content.raw))

        QrType.WIFI, QrType.TEXT, QrType.UNKNOWN -> null
    }

    /** 用 PackageManager 动态查询处理该 Intent 的应用数量 */
    private fun handlerCount(context: Context, intent: Intent): Int = try {
        context.packageManager.queryIntentActivities(intent, 0).size
    } catch (e: Exception) {
        0
    }

    /**
     * 交给系统处理。分流规则（需求对照）：
     *
     * 1. 普通 http/https 链接 → **系统标准 URL Intent**，不指定包名，由系统默认浏览器决定
     *    （用户在设置页主动指定过浏览器才用 [LinkPlan.Browser] 定向）
     * 2. 纯文本 → 什么都不做，页面内已展示 + 可复制
     * 3. 微信专属内容 → 复制 + 拉起微信；**绝不进系统选择器**（那两条同名「微信」都是死路）
     * 4. 其它 App Deep Link → 先查该 App 能否处理；能 → 交给它，不能 → 留在页内，不丢浏览器
     * 5. 支付宝码 → 用 alipayqr 把码交给支付宝
     * 6. 其它 → 有处理者才走系统选择器
     *
     * 全流程只用 Android 正规 Intent / PackageManager，不做任何破解或绕过。
     */
    fun open(context: Context, content: QrContent): OpenResult {
        if (content.type == QrType.WIFI) {
            val wifi = content.wifi ?: return OpenResult.NOT_SUPPORTED
            return connectToWifi(context, wifi)
        }

        val intent = buildIntent(content) ?: return OpenResult.NOT_SUPPORTED
        // 链接类：把「通配 http 但打不开网页」的 App（微信等）从选择器里剔掉
        val exclude = webExclusions(context, content)
        val plan = planForContent(
            raw = content.raw,
            type = content.type,
            installed = { isInstalled(context, it) },
            preferredBrowser = BrowserPref.preferred(context)
        )

        // ---- 3. 微信专属内容（含 weixin:// scheme）：复制 + 拉起微信，绝不弹选择器 ----
        if (plan is LinkPlan.WeChatCode) {
            copyToClipboard(context, content.raw)
            // 只用一条路：launchApp（显式拉微信主界面）。以前这里还额外 startActivity 过一次
            // `weixin://scanqrcode`，已删除 —— 微信给这个 scheme 注册了两个 Activity
            // （真入口不导出 + 空壳 WXCustomSchemeEntryActivity），隐式 startActivity 命中多个
            // handler 就有概率弹系统消歧框，而真机实测它一个界面都不出，纯粹是噪声。
            if (launchApp(context, plan.pkg) == OpenResult.APP_OPENED) {
                return OpenResult.APP_OPENED
            }
            return startChooser(context, intent, chooserTitle(content), exclude)
        }

        if (content.type == QrType.URL) {
            return when (plan) {
                // 5. 支付宝：把码交给支付宝自解析
                is LinkPlan.Scheme -> {
                    val r = openSchemeChain(context, plan.uris)
                    if (r == OpenResult.OK) r
                    else startChooser(context, intent, chooserTitle(content), exclude)
                }

                // 4'. App 专属域名链接：App Links → 官方 scheme → 拉起 App → 选择器（不掉浏览器）
                is LinkPlan.AppLink -> {
                    copyToClipboard(context, content.raw)
                    openAppTarget(context, intent, plan, exclude, chooserTitle(content))
                }

                // 1'. 用户主动指定过浏览器 → 定向它
                is LinkPlan.Browser -> {
                    val explicit = Intent(intent).setPackage(plan.pkg)
                    val r = startDirect(context, explicit)
                    if (r == OpenResult.OK) r
                    else startChooser(context, intent, chooserTitle(content), exclude)
                }

                // 1. 普通链接：标准 URL Intent，交给系统默认浏览器（不写死 Edge/Chrome）
                LinkPlan.SystemDefault -> {
                    val r = startDirect(context, intent)
                    if (r == OpenResult.OK) r
                    else startChooser(context, intent, chooserTitle(content), exclude)
                }

                else -> startChooser(context, intent, chooserTitle(content), exclude)
            }
        }

        // ---- 4. 非链接类 Deep Link：已知归属的 scheme 优先定向给那个 App ----
        if (content.type == QrType.APP) {
            claimedPackageFor(content.raw)
                ?.takeIf { isInstalled(context, it) }
                ?.let { pkg ->
                    val forced = Intent(intent).setPackage(pkg)
                    if (startDirect(context, forced) == OpenResult.OK) return OpenResult.OK
                    // 该 App 其实不认这个 scheme → 退回下面的通用判定
                }
        }

        val count = handlerCount(context, intent)
        return when (decideStrategy(content.type, count)) {
            OpenStrategy.DIRECT -> startDirect(context, intent)
            OpenStrategy.CHOOSER -> startChooser(context, intent, chooserTitle(content), exclude)
            // 2 / 4. 没有应用能处理 → 不打开任何东西，也不丢浏览器：
            //        结果页上原始内容与「复制」都在，提示用户即可
            OpenStrategy.SHOW_ONLY -> OpenResult.NO_HANDLER
        }
    }

    /**
     * 「也可以交给」：把内容交给用户指定的 App（只在用户主动点击时调用）。
     *
     * 支付宝：走它**公开注册**的收码入口 `alipayqr://platformapi/startapp?saId=10000007&qrcode=<码>`
     * （saId=10000007 就是扫一扫），支付宝会自己解析这段内容。真机实测对**任意**内容都能拉起
     * 并渲染（落到 `XRiverActivity`）。这是正常 Android Intent 调用，不涉及任何破解。
     * 微信：**没有**等价入口（已确认 `weixin://` 下唯一相关组件不导出），只能复制内容 + 拉起微信，
     * 由用户自己用扫一扫或粘贴。
     */
    fun openAlt(context: Context, content: QrContent, alt: AltApp): OpenResult = when (alt) {
        AltApp.ALIPAY -> {
            val r = openSchemeChain(context, alipayChain(content.raw))
            if (r == OpenResult.OK) r else launchApp(context, PKG_ALIPAY)
        }

        AltApp.WECHAT -> {
            // 普通 URL：优先用微信内置浏览器直接打开（ACTION_VIEW + setPackage），
            // 比"复制 + 手动扫一扫"体验好得多。
            // 微信专属域名不走这里（planForContent 已把它归入 WeChatCode，不会出现在 altApps 中）。
            if (content.type == QrType.URL) {
                val direct = openInWeChat(context, content.raw)
                if (direct == OpenResult.OK) direct
                else {
                    // 微信拒绝了这个 URL → 回退到老路：复制 + 拉起微信
                    copyToClipboard(context, content.raw)
                    launchApp(context, PKG_WECHAT)
                }
            } else {
                copyToClipboard(context, content.raw)
                launchApp(context, PKG_WECHAT)
            }
        }
    }

    /** 已知归属的 scheme → 包名（见顶层 [claimedPackage]） */
    private fun claimedPackageFor(raw: String): String? = claimedPackage(raw)

    /** 始终弹系统选择器：结果页「选择其他应用打开」用，保证用户永远能自己挑 */
    fun openWithChooser(context: Context, content: QrContent): OpenResult {
        val intent = buildIntent(content) ?: return OpenResult.NOT_SUPPORTED
        return startChooser(
            context, intent, chooserTitle(content), webExclusions(context, content)
        )
    }

    /**
     * 枚举真正能打开当前内容的应用，供**应用内**选择器使用。
     *
     * 为什么不能直接靠系统选择器 `Intent.createChooser`：AOSP 的 `ChooserActivity` 在
     * **只有一个候选**时会直接启动它、压根不弹界面。而 ColorOS 在 PackageManager 层对
     * http/https 的处理者查询只返回系统浏览器一个（真机实测），于是「选择其他应用打开」
     * 会静默退化成「直接用浏览器打开」—— 用户看到的就是「根本选不了，还是直接进浏览器」。
     *
     * 这里自己列：系统查询结果 ∪ 已安装的已知浏览器（绕开 ROM 对查询的隐藏），
     * 选中后走 [openWithPackage] 的 setPackage 直启。
     */
    fun chooserApps(context: Context, content: QrContent): List<AppChoice> {
        val intent = buildIntent(content) ?: return emptyList()
        val pm = context.packageManager
        val resolved = try {
            pm.queryIntentActivities(intent, 0).mapNotNull { ri ->
                val pkg = ri.activityInfo.packageName ?: return@mapNotNull null
                AppChoice(pkg, ri.loadLabel(pm).toString())
            }
        } catch (e: Exception) {
            // ROM 限制查询时忽略，下面还有已知浏览器清单兜底
            emptyList()
        }
        val isUrl = content.type == QrType.URL
        val browsers = if (isUrl) {
            BrowserPref.listBrowsers(context).map { AppChoice(it.packageName, it.label) }
        } else {
            emptyList()
        }
        return mergeChooserApps(resolved, browsers, isUrl, context.packageName)
    }

    /** 用指定包打开当前内容（setPackage 直启，绕开被 ROM 阉割的系统选择器） */
    fun openWithPackage(context: Context, content: QrContent, packageName: String): OpenResult {
        val intent = buildIntent(content)?.setPackage(packageName) ?: return OpenResult.NOT_SUPPORTED
        return startDirect(context, intent)
    }

    /**
     * 应用专属链接的逐级兜底（顺序很重要）：
     * 1. App Links —— 指定包名直接用原链接打开（App 注册了对应域名时最完美）
     * 2. 官方 scheme —— 例如淘宝 `taobao://m.tb.cn/xxx`、拼多多 `pinduoduo://包名/<链接>`
     * 3. 至少把 App 拉起来（内容已复制）—— 返回 APP_OPENED，界面提示用户去 App 内粘贴
     * 4. 真的没辙才弹系统选择器
     *
     * 注意第 3 步：**不要在这一步掉到浏览器**。用户扫的是美团码，跳浏览器看「请用美团打开」
     * 是最糟糕的体验 —— 宁可先把 App 打开、把链接复制好。
     */
    private fun openAppTarget(
        context: Context,
        intent: Intent,
        plan: LinkPlan.AppLink,
        exclude: List<String>,
        title: String
    ): OpenResult {
        if (startDirect(context, Intent(intent).setPackage(plan.pkg)) == OpenResult.OK) {
            return OpenResult.OK
        }
        for (uri in plan.fallbackUris) {
            val forced = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(plan.pkg)
            if (startDirect(context, forced) == OpenResult.OK) return OpenResult.OK
        }
        if (launchApp(context, plan.pkg) == OpenResult.APP_OPENED) return OpenResult.APP_OPENED
        return startChooser(context, intent, title, exclude)
    }

    /** 按顺序尝试一串 scheme，任一成功即返回 OK */
    private fun openSchemeChain(context: Context, uris: List<String>): OpenResult {
        var last: OpenResult = OpenResult.NO_HANDLER
        for (uri in uris) {
            val r = startDirect(context, Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
            if (r == OpenResult.OK) return OpenResult.OK
            last = r
        }
        return last
    }

    private fun startDirect(context: Context, intent: Intent): OpenResult = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        OpenResult.OK
    } catch (e: ActivityNotFoundException) {
        OpenResult.NO_HANDLER
    } catch (e: Exception) {
        OpenResult.FAILED
    }

    /** 链接类内容弹选择器时，要剔掉的「假处理者」组件（微信这类通配 http 却打不开网页的） */
    private fun webExclusions(context: Context, content: QrContent): List<String> =
        if (content.type == QrType.URL) BrowserPref.fakeWebComponentNames(context) else emptyList()

    private fun startChooser(
        context: Context,
        intent: Intent,
        title: String,
        excludeComponents: List<String> = emptyList()
    ): OpenResult = try {
        val chooser = Intent.createChooser(intent, title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (excludeComponents.isNotEmpty()) {
            val names = excludeComponents
                .mapNotNull { ComponentName.unflattenFromString(it) }
                .toTypedArray()
            if (names.isNotEmpty()) {
                chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, names)
            }
        }
        context.startActivity(chooser)
        OpenResult.OK
    } catch (e: ActivityNotFoundException) {
        OpenResult.NO_HANDLER
    } catch (e: Exception) {
        OpenResult.FAILED
    }

    /** 应用内选择器的标题（也用于系统选择器） */
    fun chooserTitle(content: QrContent): String = when (content.type) {
        QrType.URL -> "选择浏览器或应用打开"
        QrType.GEO -> "选择地图应用"
        else -> "选择应用打开"
    }

    /** 系统标准分享面板 */
    fun share(context: Context, content: QrContent, title: String = "分享") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, content.copyText)
        }
        try {
            context.startActivity(Intent.createChooser(intent, title))
        } catch (e: Exception) {
            // 忽略：极少数设备无分享目标
        }
    }
}
