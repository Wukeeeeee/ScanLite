package com.scan.qr

import com.scan.qr.scanner.OpenStrategy
import com.scan.qr.scanner.QrActions
import com.scan.qr.scanner.QrRoutingKind
import com.scan.qr.scanner.QrType
import com.scan.qr.scanner.AltApp
import com.scan.qr.scanner.altAppsForContent
import com.scan.qr.scanner.appNameForContent
import com.scan.qr.scanner.categoryLabel
import com.scan.qr.scanner.decideStrategy
import com.scan.qr.scanner.label
import com.scan.qr.scanner.parseQrContent
import com.scan.qr.scanner.planForContent
import com.scan.qr.scanner.planHint
import com.scan.qr.scanner.routingKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenDecisionTest {

    @Test
    fun urlTypeRoutesToChooserBranchNotDirect() {
        // 链接类由 planForUrl 单独处理，这里只保证不会掉进"直接打开"分支
        assertEquals(OpenStrategy.CHOOSER, decideStrategy(QrType.URL, 1))
        assertEquals(OpenStrategy.CHOOSER, decideStrategy(QrType.URL, 0))
    }

    @Test
    fun deepLinkWithSingleHandlerJumpsDirectly() {
        // 装了微信/支付宝（唯一处理应用）→ 直接跳转
        assertEquals(OpenStrategy.DIRECT, decideStrategy(QrType.APP, 1))
    }

    @Test
    fun deepLinkWithMultipleHandlersAsks() {
        assertEquals(OpenStrategy.CHOOSER, decideStrategy(QrType.APP, 2))
        assertEquals(OpenStrategy.CHOOSER, decideStrategy(QrType.APP, 5))
    }

    @Test
    fun noHandlerAtAllShowsContentOnlyInsteadOfOpeningSomething() {
        // 一个处理应用都没有 → 不打开任何东西（更不许丢浏览器）：
        // 结果页上原始内容与「复制」都在，open() 会返回 NO_HANDLER 由界面提示
        assertEquals(OpenStrategy.SHOW_ONLY, decideStrategy(QrType.APP, 0))
        assertEquals(OpenStrategy.SHOW_ONLY, decideStrategy(QrType.TEL, 0))
        assertEquals(OpenStrategy.SHOW_ONLY, decideStrategy(QrType.SMS, 0))
        assertEquals(OpenStrategy.SHOW_ONLY, decideStrategy(QrType.GEO, 0))
    }

    @Test
    fun knownAppSchemesAreRecognizedForLabelOnly() {
        assertEquals("微信", appNameForContent("weixin://dl/business/?t=xxx"))
        assertEquals("支付宝", appNameForContent("alipays://platformapi/startapp?appId=20000056"))
        assertEquals("支付宝", appNameForContent("alipayqr://platformapi/startapp?saId=10000007"))
        assertEquals("美团单车", appNameForContent("meituanbike://..."))
        assertEquals("淘宝", appNameForContent("taobao://item.taobao.com/item.htm?id=1"))
        assertEquals("QQ", appNameForContent("mqq://..."))
        assertNull(appNameForContent("myapp-unknown://open"))
        assertNull(appNameForContent("https://example.com"))
    }

    @Test
    fun appTypeButtonSaysWhichApp() {
        assertEquals("用微信打开", QrActions.primaryLabel(parseQrContent("weixin://dl/scan")))
        assertEquals("用支付宝打开", QrActions.primaryLabel(parseQrContent("alipays://x")))
        // 未知 scheme 用通用文案
        assertEquals("打开", QrActions.primaryLabel(parseQrContent("unknownscheme://x")))
    }

    @Test
    fun urlIsClassifiedAsUrlSoItRoutesThroughTheLinkPlan() {
        assertEquals(QrType.URL, parseQrContent("https://example.com/a").type)
        assertEquals(QrType.URL, parseQrContent("http://example.com").type)
        assertEquals(QrType.URL, parseQrContent("https://u.wechat.com/abc").type)
        assertEquals(QrType.URL, parseQrContent("https://qr.alipay.com/abc").type)
    }

    @Test
    fun urlButtonLabelIsGenericBecausePlanDecidesTheRealOne() {
        // 链接类的主按钮文案由 ResultScreen 用 planLabel(linkPlan(...)) 决定（见 BrowserPlanTest），
        // primaryLabel 只作为兜底，保持通用文案。
        assertEquals("打开网页", QrActions.primaryLabel(parseQrContent("https://u.wechat.com/abc")))
        assertEquals("打开网页", QrActions.primaryLabel(parseQrContent("https://example.com")))
    }

    // ---------- 分流粗分类：识别结果 → 该交给谁 ----------

    @Test
    fun routingKindCoversEveryCategory() {
        // 1 网页链接（http 与 https 同等对待）
        assertEquals(QrRoutingKind.WEB_URL, routingKind("https://example.com/a", QrType.URL))
        assertEquals(QrRoutingKind.WEB_URL, routingKind("http://example.com", QrType.URL))

        // 2 普通文本：绝不打开浏览器
        assertEquals(QrRoutingKind.PLAIN_TEXT, routingKind("hello 世界", QrType.TEXT))
        assertEquals("普通文本", QrRoutingKind.PLAIN_TEXT.label())

        // 3 微信专用内容：域名 + scheme 两种形态都要认出来
        assertEquals(QrRoutingKind.WECHAT, routingKind("https://u.wechat.com/abc", QrType.URL))
        assertEquals(QrRoutingKind.WECHAT, routingKind("http://weixin.qq.com/r/abc", QrType.URL))
        assertEquals(QrRoutingKind.WECHAT, routingKind("weixin://qr/abc", QrType.APP))
        assertEquals(QrRoutingKind.WECHAT, routingKind("wechat://qr/abc", QrType.APP))

        // 4 其它 App 的 Deep Link
        assertEquals(QrRoutingKind.APP_DEEP_LINK, routingKind("taobao://m.tb.cn/x", QrType.APP))
        assertEquals(QrRoutingKind.APP_DEEP_LINK, routingKind("alipayqr://x", QrType.APP))
        assertEquals(QrRoutingKind.APP_DEEP_LINK, routingKind("unknownapp://x", QrType.APP))

        // 5 系统操作
        assertEquals(QrRoutingKind.SYSTEM_ACTION, routingKind("tel:10086", QrType.TEL))
        assertEquals(QrRoutingKind.SYSTEM_ACTION, routingKind("mailto:a@b.c", QrType.EMAIL))
        assertEquals(QrRoutingKind.SYSTEM_ACTION, routingKind("geo:1,2", QrType.GEO))

        // 6 未知（被禁 scheme 会被解析层判成 UNKNOWN）
        assertEquals(QrRoutingKind.UNKNOWN, routingKind("javascript:alert(1)", QrType.UNKNOWN))
    }

    @Test
    fun wechatSchemeIsNeverClassifiedAsPlainDeepLink() {
        // 这条很关键：微信 scheme 一旦被当成普通 Deep Link，就会被丢进系统选择器 →
        // 「弹了选项、点了微信、跳转不了」。必须永远走 WECHAT 分类。
        for (raw in listOf("weixin://qr/x", "weixin://dl/scan", "weixin://scanqrcode", "weixin://pay/x")) {
            assertEquals("应识别为微信专用: $raw", QrRoutingKind.WECHAT, routingKind(raw, QrType.APP))
        }
    }

    @Test
    fun textAndWifiNeverProduceAnOpenAction() {
        // 需求 2：纯文本不打开浏览器，只在页内展示 + 复制
        val text = parseQrContent("这是一段普通文本")
        assertEquals(QrType.TEXT, text.type)
        assertTrue(!QrActions.hasPrimaryAction(text))
        assertNull(planForContent(text.raw, text.type, { true }, null))
        assertEquals("", QrActions.primaryLabel(text))

        // Wi-Fi 同理：只展示，不外跳
        val wifi = parseQrContent("WIFI:S:MyNet;T:WPA;P:12345678;;")
        assertEquals(QrType.WIFI, wifi.type)
        assertTrue(!QrActions.hasPrimaryAction(wifi))
        assertNull(planForContent(wifi.raw, wifi.type, { true }, null))
    }

    @Test
    fun wechatCodeShowsAsWechatCategoryNotPlainUrl() {
        // 用户反馈：微信好友码顶部分类写着「网页链接」，和下面「复制并打开微信」自相矛盾
        val wechat = parseQrContent("https://u.wechat.com/MMLB2g6DkAr2Vr1mja7WoA?s=4")
        assertEquals(QrType.URL, wechat.type)          // 解析层仍然是 URL
        assertEquals("微信专用内容", categoryLabel(wechat))  // 展示层必须是微信

        // 其它类型保持原来的具体名字，不能被这条规则改坏
        assertEquals("网页链接", categoryLabel(parseQrContent("https://www.example.com")))
        assertEquals("电话号码", categoryLabel(parseQrContent("tel:+8613800138000")))
        assertEquals("文本", categoryLabel(parseQrContent(" hello ")))
    }

    @Test
    fun wechatHintTalksToTheUserNotToTheDeveloper() {
        // 用户反馈：提示里不该出现 exported=false / 类名这种东西，要讲"我该点哪"
        val hint = planHint(planForContent(
            "https://u.wechat.com/MMLB2g6DkAr2Vr1mja7WoA?s=4", QrType.URL, { true }, null
        )!!)!!
        assertTrue("不该出现内部类名: $hint", !hint.contains("Exported") && !hint.contains("exported"))
        assertTrue("不该出现内部类名: $hint", !hint.contains("QRCodeInfoUI"))
        assertTrue("要告诉用户下一步点哪: $hint", hint.contains("扫一扫"))
    }

    @Test
    fun ambiguousCodeOffersBothAlipayAndWechat() {
        // 用户提出的场景：有些码谁都不专属归属（聚合付款码/商家码/普通短链），
        // 丢给支付宝或微信都合理 → 不能替用户猜，要把选择权交回去
        val all = { _: String -> true }
        val url = parseQrContent("https://example.com/pay")
        assertEquals(emptyList<AltApp>(), altAppsForContent(url.raw, QrType.TEXT, all))  // 非链接类不给
        assertEquals(
            listOf(AltApp.ALIPAY, AltApp.WECHAT),
            altAppsForContent(url.raw, url.type, all)
        )
        // 没装的 App 不能出现在候选里
        val onlyWeChat = { p: String -> p == "com.tencent.mm" }
        assertEquals(listOf(AltApp.WECHAT), altAppsForContent(url.raw, url.type, onlyWeChat))
        assertEquals(emptyList<AltApp>(), altAppsForContent(url.raw, url.type, { false }))

        // 未知自定义 scheme 同理（主按钮只能弹系统选择器）
        assertEquals(
            listOf(AltApp.ALIPAY, AltApp.WECHAT),
            altAppsForContent("foobar://unknown", QrType.APP, all)
        )
    }

    @Test
    fun uniqueOwnerNeverShowsTheExtraRow() {
        // 有专属归属的码不该出现「也可以交给」——那是画蛇添足，还会让人犹豫
        val all = { _: String -> true }
        for (raw in listOf(
            "https://u.wechat.com/MMLB2g6DkAr2Vr1mja7WoA?s=4",   // 微信专属
            "weixin://qr/abc",                                     // 微信 scheme
            "https://qr.alipay.com/fkx1a1b2c3d4e5f6",              // 支付宝
            "https://dpurl.cn/abcdefg"                             // 美团 App Link
        )) {
            val c = parseQrContent(raw)
            assertEquals("不应给候选: $raw", emptyList<AltApp>(), altAppsForContent(c.raw, c.type, all))
        }
        // 自定义 scheme 已知归属时，主按钮已经指向它 → 不要重复列那一个
        val alipayScheme = parseQrContent("alipays://platformapi/startapp?saId=10000007")
        assertEquals(
            listOf(AltApp.WECHAT),
            altAppsForContent(alipayScheme.raw, alipayScheme.type, all)
        )
    }

    @Test
    fun altButtonLabelsAreAppNames() {
        assertEquals("支付宝", AltApp.ALIPAY.label())
        assertEquals("微信", AltApp.WECHAT.label())
    }
}
