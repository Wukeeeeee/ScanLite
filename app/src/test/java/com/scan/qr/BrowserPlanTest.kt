package com.scan.qr

import com.scan.qr.scanner.BrowserChoice
import com.scan.qr.scanner.ComponentRef
import com.scan.qr.scanner.HandlerRef
import com.scan.qr.scanner.LinkPlan
import com.scan.qr.scanner.PKG_ALIPAY
import com.scan.qr.scanner.PKG_WECHAT
import com.scan.qr.scanner.QrType
import com.scan.qr.scanner.fakeWebComponents
import com.scan.qr.scanner.expandSchemeTemplate
import com.scan.qr.scanner.isWeChatScheme
import com.scan.qr.scanner.mergeBrowsers
import com.scan.qr.scanner.parseQrContent
import com.scan.qr.scanner.pickLauncherComponent
import com.scan.qr.scanner.planForContent
import com.scan.qr.scanner.planForUrl
import com.scan.qr.scanner.planHint
import com.scan.qr.scanner.planLabel
import com.scan.qr.scanner.urlHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPlanTest {

    private fun installed(vararg pkgs: String): (String) -> Boolean = { it in pkgs.toSet() }

    // ---------- 微信 weixin:// scheme：与网页码同命运，绝不能进系统选择器 ----------

    @Test
    fun wechatSchemeContentIsRecognised() {
        // 真机取证：weixin://qr/<码> 在系统里能解析出**两个都叫「微信」的过滤器**
        // （不可导出的 GetQRCodeInfoUI + 空壳 WXCustomSchemeEntryActivity），
        // 丢进选择器就是「弹了选项、点了微信、跳转不了」。必须识别出来走复制+拉起。
        assertTrue(isWeChatScheme("weixin://qr/ABC123"))
        assertTrue(isWeChatScheme("wechat://qr/ABC123"))
        assertTrue(isWeChatScheme("WEIXIN://qr/ABC123"))
        assertTrue(!isWeChatScheme("alipayqr://platformapi/startapp"))
        assertTrue(!isWeChatScheme("https://u.wechat.com/abc"))
    }

    @Test
    fun wechatSchemeContentPlansToWeChatCode() {
        val content = parseQrContent("weixin://qr/MMLB2g6DkAr2Vr1mja7WoA")
        assertEquals(QrType.APP, content.type)
        assertEquals(
            LinkPlan.WeChatCode(PKG_WECHAT),
            planForContent(content.raw, content.type, installed(PKG_WECHAT), null)
        )
    }

    @Test
    fun wechatSchemeContentFallsBackWhenWechatMissing() {
        val content = parseQrContent("weixin://qr/ABC123")
        assertNull(planForContent(content.raw, content.type, installed(), "com.microsoft.emmx"))
    }

    @Test
    fun nonWeChatAppSchemeKeepsOldBehaviour() {
        // 支付宝系 scheme 不属于微信内容，不能被抓进 WeChatCode 分支
        val content = parseQrContent("alipayqr://platformapi/startapp?saId=10000007")
        assertEquals(QrType.APP, content.type)
        assertNull(planForContent(content.raw, content.type, installed(PKG_ALIPAY), null))
    }

    @Test
    fun textAndWifiContentHaveNoPlan() {
        assertNull(planForContent("hello world", QrType.TEXT, installed(PKG_WECHAT), null))
        assertNull(planForContent("WIFI:S:x;T:WPA;P:y;;", QrType.WIFI, installed(PKG_WECHAT), null))
    }

    // ---------- 启动组件解析（launchApp 的兜底路径）----------

    @Test
    fun pickLauncherComponentMatchesPackageExactly() {
        val list = listOf(
            ComponentRef("com.android.chrome", "com.android.chrome.Main"),
            ComponentRef("com.tencent.mm", "com.tencent.mm.ui.LauncherUI")
        )
        assertEquals(
            ComponentRef("com.tencent.mm", "com.tencent.mm.ui.LauncherUI"),
            pickLauncherComponent(list, "com.tencent.mm")
        )
        // 前缀相同的包名不能误配
        assertNull(pickLauncherComponent(list, "com.tencent"))
        assertNull(pickLauncherComponent(list, "com.tencent.mm.plugin"))
        assertNull(pickLauncherComponent(emptyList(), "com.tencent.mm"))
    }

    // ---------- 微信：没有任何直达方案，只能复制 + 拉起微信 ----------

    @Test
    fun wechatLinkNeverGoesToBrowser() {
        // 微信专属链接绝不能丢给浏览器（否则只会看到「请用微信扫一扫」）
        val plan = planForUrl("https://u.wechat.com/ABC123", installed(PKG_WECHAT), null)
        assertEquals(LinkPlan.WeChatCode(PKG_WECHAT), plan)
    }

    @Test
    fun wechatLinkStillWeChatEvenWithDefaultBrowser() {
        val plan = planForUrl("http://weixin.qq.com/r/xyz", installed(PKG_WECHAT), "com.microsoft.emmx")
        assertEquals(LinkPlan.WeChatCode(PKG_WECHAT), plan)
    }

    @Test
    fun wechatLinkFallsBackWhenWechatMissing() {
        val plan = planForUrl("https://u.wechat.com/ABC123", installed(), "com.microsoft.emmx")
        assertEquals(LinkPlan.Browser("com.microsoft.emmx"), plan)
    }

    @Test
    fun wechatHostMatchingHandlesSubdomains() {
        for (url in listOf(
            "https://u.wechat.com/a",
            "https://weixin.qq.com/r/a",
            "https://mp.weixin.qq.com/s/a",
            "https://w.url.cn/s/abc"
        )) {
            assertEquals(
                "应识别为微信码: $url",
                LinkPlan.WeChatCode(PKG_WECHAT),
                planForUrl(url, installed(PKG_WECHAT), null)
            )
        }
        // 相似但不同的域名不能误判
        assertNull(planForUrl("https://notweixin.qq.com/r/a", installed(PKG_WECHAT), null) as? LinkPlan.WeChatCode)
        // 伪域名（把已知域名当后缀拼在攻击者域名前）绝对不能命中
        assertNull(
            planForUrl("https://evil-u.wechat.com.attacker.io/x", installed(PKG_WECHAT), null)
                as? LinkPlan.WeChatCode
        )
    }

    // ---------- 支付宝：把码交给支付宝 ----------

    @Test
    fun alipayLinkHandsTheCodeToAlipay() {
        val plan = planForUrl("https://qr.alipay.com/abc123", installed(PKG_ALIPAY), null)
        assertTrue(plan is LinkPlan.Scheme)
        val scheme = plan as LinkPlan.Scheme
        assertEquals("支付宝", scheme.appName)
        assertEquals("用支付宝打开", scheme.label)
        // 把二维码内容原样编码后交给支付宝扫一扫入口
        assertTrue(scheme.uris.first().startsWith("alipayqr://platformapi/startapp?saId=10000007&qrcode="))
        assertTrue(scheme.uris.first().contains("https%3A%2F%2Fqr.alipay.com%2Fabc123"))
    }

    // ---------- 其它 App：App Links 直达 ----------

    @Test
    fun appLinkDomainsJumpStraightToTheApp() {
        val plan = planForUrl("https://m.tb.cn/h.abcdef", installed("com.taobao.taobao"), null)
        assertTrue(plan is LinkPlan.AppLink)
        assertEquals("com.taobao.taobao", (plan as LinkPlan.AppLink).pkg)
        assertEquals("用淘宝打开", plan.label)
    }

    // ---------- 短链域名（街上扫到的码多半是短链，漏一个就掉浏览器） ----------

    @Test
    fun meituanShortLinkIsRecognisedAsMeituan() {
        // dpurl.cn 是美团/点评自家短链（备案主体上海汉涛），「美团码」最常见就是这个域名
        val plan = planForUrl("http://dpurl.cn/Dku2l3Dz", installed("com.sankuai.meituan"), null)
        assertTrue("dpurl.cn 必须识别为美团，否则会掉浏览器", plan is LinkPlan.AppLink)
        assertEquals("com.sankuai.meituan", (plan as LinkPlan.AppLink).pkg)
        assertEquals("用美团打开", plan.label)
    }

    @Test
    fun meituanSubdomainsAndSankuaiAreRecognised() {
        for (url in listOf(
            "https://i.meituan.com/c/abc?lch=sms",
            "https://waimai.meituan.com/getapp/1012",
            "https://www.meituan.com/meishi/123",
            "https://m.dianping.com/shop/12345",
            "https://meituan.net/x",
            "https://sankuai.com/y"
        )) {
            val plan = planForUrl(url, installed("com.sankuai.meituan"), null)
            assertTrue("应识别为美团: $url", plan is LinkPlan.AppLink)
        }
    }

    @Test
    fun otherShortLinkDomainsAreCovered() {
        assertEquals(
            "com.taobao.taobao",
            (planForUrl("https://tb.cn/abc", installed("com.taobao.taobao"), null) as LinkPlan.AppLink).pkg
        )
        assertEquals(
            "tv.danmaku.bili",
            (planForUrl("https://b23.tv/abc", installed("tv.danmaku.bili"), null) as LinkPlan.AppLink).pkg
        )
        assertEquals(
            "com.xingin.xhs",
            (planForUrl("https://xhslink.com/abc", installed("com.xingin.xhs"), null) as LinkPlan.AppLink).pkg
        )
    }

    // ---------- scheme 兜底链 ----------

    @Test
    fun taobaoFallbackUsesSchemeSwap() {
        // 公开写法：taobao:// + 原链接去掉 scheme
        val plan = planForUrl("https://m.tb.cn/h.abcdef", installed("com.taobao.taobao"), null)
            as LinkPlan.AppLink
        assertEquals(listOf("taobao://m.tb.cn/h.abcdef"), plan.fallbackUris)
    }

    @Test
    fun pinduoduoFallbackKeepsWholeUrl() {
        val plan = planForUrl(
            "https://mobile.yangkeduo.com/goods.html?goods_id=1",
            installed("com.xunmeng.pinduoduo"), null
        ) as LinkPlan.AppLink
        assertEquals(
            listOf("pinduoduo://com.xunmeng.pinduoduo/https://mobile.yangkeduo.com/goods.html?goods_id=1"),
            plan.fallbackUris
        )
    }

    @Test
    fun appsWithoutVerifiedSchemeGetNoInventedFallback() {
        // 政策：没有公开资料佐证的 scheme 一律不写。微信/美团这种「能解析但点了没反应」的
        // scheme 比跳浏览器更糟，所以它们的兜底链必须是空的，只能靠 App Links 或拉起 App。
        val meituan = planForUrl("https://i.meituan.com/c/abc", installed("com.sankuai.meituan"), null)
            as LinkPlan.AppLink
        assertTrue(meituan.fallbackUris.isEmpty())
        val weibo = planForUrl("https://weibo.com/1/abc", installed("com.sina.weibo"), null)
            as LinkPlan.AppLink
        assertTrue(weibo.fallbackUris.isEmpty())
    }

    @Test
    fun schemeTemplatePlaceholdersExpand() {
        assertEquals("taobao://m.tb.cn/h.abc", expandSchemeTemplate("taobao://{rest}", "https://m.tb.cn/h.abc"))
        assertEquals(
            "pinduoduo://com.xunmeng.pinduoduo/https://x.com/a?b=1",
            expandSchemeTemplate("pinduoduo://com.xunmeng.pinduoduo/{raw}", "https://x.com/a?b=1")
        )
        assertEquals(
            "app://open?url=https%3A%2F%2Fx.com%2Fa",
            expandSchemeTemplate("app://open?url={url}", "https://x.com/a")
        )
    }

    @Test
    fun appLinkFallsBackWhenAppMissing() {
        val plan = planForUrl("https://m.tb.cn/h.abcdef", installed(), null)
        assertEquals(LinkPlan.SystemDefault, plan)
    }

    // ---------- 普通网址：交给系统默认浏览器，不写死具体浏览器 ----------

    @Test
    fun preferredBrowserIsUsedSilently() {
        val plan = planForUrl("https://example.com/a", installed(), "com.microsoft.emmx")
        assertEquals(LinkPlan.Browser("com.microsoft.emmx"), plan)
    }

    @Test
    fun plainUrlGoesToSystemDefaultBrowser() {
        // 没有用户指定 → 系统标准 URL Intent（不指定包名），由系统默认浏览器决定
        assertEquals(LinkPlan.SystemDefault, planForUrl("https://example.com/a", installed(), null))
        assertEquals(LinkPlan.SystemDefault, planForUrl("https://example.com/a", installed(), ""))
        assertEquals(LinkPlan.SystemDefault, planForUrl("http://example.com/a", installed(), "   "))
        assertEquals("打开链接", planLabel(LinkPlan.SystemDefault))
        assertNull(planHint(LinkPlan.SystemDefault))
    }

    // ---------- 浏览器列表合并（绕开国产 ROM 隐藏查询结果） ----------

    @Test
    fun mergedBrowserListKeepsQueryResultsFirst() {
        val resolved = listOf(BrowserChoice("com.heytap.browser", "浏览器"))
        val known = listOf(BrowserChoice("com.microsoft.emmx", "Edge"))
        val merged = mergeBrowsers(resolved, known, "com.scan.qr")
        assertEquals(listOf("Edge", "浏览器"), merged.map { it.label })
    }

    @Test
    fun mergedBrowserListDropsSelfAndDuplicates() {
        val resolved = listOf(
            BrowserChoice("com.scan.qr", "二维码扫描"),
            BrowserChoice("com.microsoft.emmx", "Edge")
        )
        val known = listOf(
            BrowserChoice("com.microsoft.emmx", "Edge 备用名"),
            BrowserChoice("com.android.chrome", "Chrome")
        )
        val merged = mergeBrowsers(resolved, known, "com.scan.qr")
        // 自己不能出现在列表里；同包名只保留系统给的名字
        assertEquals(listOf("Chrome", "Edge"), merged.map { it.label })
        assertTrue(merged.none { it.packageName == "com.scan.qr" })
    }

    // ---------- 文案 ----------

    @Test
    fun labelsCoverEveryPlan() {
        assertEquals("复制并打开微信", planLabel(LinkPlan.WeChatCode(PKG_WECHAT)))
        assertEquals("用支付宝打开", planLabel(LinkPlan.Scheme(listOf("alipayqr://"), "支付宝", "用支付宝打开")))
        assertEquals("用淘宝打开", planLabel(LinkPlan.AppLink("com.taobao.taobao", "淘宝", "用淘宝打开")))
        assertEquals("用默认浏览器打开", planLabel(LinkPlan.Browser("com.microsoft.emmx")))
        assertEquals("打开网页", planLabel(LinkPlan.Chooser))
    }

    @Test
    fun wechatPlanExplainsWhyItNeedsWechat() {
        val hint = planHint(LinkPlan.WeChatCode(PKG_WECHAT))
        assertTrue(hint != null && hint.contains("微信"))
        // 用户反馈过：这段话以前在给用户看"exported=false / 类名"这种开发者向的说明，
        // 现在必须是一句人话 —— 说清"微信不开放，所以没法一键跳进去" + "你接下来点哪"。
        assertTrue(hint!!.contains("扫一扫"))
        assertTrue(!hint.contains("exported") && !hint.contains("Exported"))
        // 普通计划不需要额外说明
        assertNull(planHint(LinkPlan.Chooser))
        assertNull(planHint(LinkPlan.Browser("com.microsoft.emmx")))
    }

    // ---------- host 解析 ----------

    @Test
    fun urlHostParsing() {
        assertEquals("example.com", urlHost("https://www.example.com/a/b?c=1"))
        assertEquals("example.com", urlHost("http://example.com:8080/x"))
        assertEquals("u.wechat.com", urlHost("  https://U.WeChat.com/ABC  "))
        assertNull(urlHost("weixin://dl/scan"))
        assertNull(urlHost("plain text"))
    }

    // ---------- 从选择器里剔除「假网页处理者」 ----------

    private val wechatHttp = HandlerRef(
        "com.tencent.mm", "com.tencent.mm/com.tencent.mm.plugin.scanner.WXCustomSchemeEntryActivity"
    )
    private val heytapHttps = HandlerRef("com.heytap.browser", "com.heytap.browser/.MainActivity")

    @Test
    fun wechatIsDroppedFromChooser() {
        // 真机实测：微信只注册了通配 http，入口是空壳 Activity，点下去没有任何反应。
        // 规则：能 http 不能 https、且不是已知浏览器 → 踢出选择器。
        val fake = fakeWebComponents(
            httpHandlers = listOf(wechatHttp, heytapHttps),
            httpsHandlers = listOf(heytapHttps),
            knownBrowserPackages = emptySet()
        )
        assertEquals(
            listOf("com.tencent.mm/com.tencent.mm.plugin.scanner.WXCustomSchemeEntryActivity"),
            fake
        )
    }

    @Test
    fun realBrowserSurvivesBecauseItHandlesHttps() {
        val fake = fakeWebComponents(
            httpHandlers = listOf(heytapHttps),
            httpsHandlers = listOf(heytapHttps),
            knownBrowserPackages = emptySet()
        )
        assertTrue(fake.isEmpty())
    }

    @Test
    fun knownBrowserOnHttpOnlyRomIsStillKept() {
        // 极端情况：ROM 只在 http 查询里返回某浏览器 → 靠「已知浏览器」白名单保住
        val fake = fakeWebComponents(
            httpHandlers = listOf(heytapHttps),
            httpsHandlers = emptyList(),
            knownBrowserPackages = setOf("com.heytap.browser")
        )
        assertTrue(fake.isEmpty())
    }

    @Test
    fun fakeHandlersAreDeduplicatedAndEmptyWhenNothingMatches() {
        val fake = fakeWebComponents(
            httpHandlers = listOf(wechatHttp, wechatHttp),
            httpsHandlers = emptyList(),
            knownBrowserPackages = emptySet()
        )
        assertEquals(1, fake.size)
        assertTrue(fakeWebComponents(emptyList(), emptyList(), emptySet()).isEmpty())
    }
}
