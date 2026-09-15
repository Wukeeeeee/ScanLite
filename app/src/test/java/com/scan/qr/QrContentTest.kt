package com.scan.qr

import com.scan.qr.scanner.QrActions
import com.scan.qr.scanner.QrType
import com.scan.qr.scanner.parseQrContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrContentTest {

    @Test
    fun plainText() {
        val c = parseQrContent("Hello World")
        assertEquals(QrType.TEXT, c.type)
        assertEquals("Hello World", c.display)
    }

    @Test
    fun httpsUrl() {
        val c = parseQrContent("https://example.com")
        assertEquals(QrType.URL, c.type)
        assertEquals("https://example.com", c.display)
    }

    @Test
    fun httpUrl() {
        val c = parseQrContent("http://example.com/path?a=1")
        assertEquals(QrType.URL, c.type)
    }

    @Test
    fun tel() {
        val c = parseQrContent("tel:10086")
        assertEquals(QrType.TEL, c.type)
        assertEquals("10086", c.display)
    }

    @Test
    fun mailto() {
        val c = parseQrContent("mailto:test@example.com")
        assertEquals(QrType.EMAIL, c.type)
        assertEquals("test@example.com", c.display)
    }

    @Test
    fun mailtoWithSubject() {
        val c = parseQrContent("mailto:a@b.com?subject=Hi%20There&body=Hello")
        assertEquals(QrType.EMAIL, c.type)
        assertTrue(c.detail!!.contains("主题：Hi There"))
    }

    @Test
    fun geo() {
        val c = parseQrContent("geo:28.2282,112.9388")
        assertEquals(QrType.GEO, c.type)
        assertEquals("28.2282, 112.9388", c.display)
    }

    @Test
    fun wifi() {
        val c = parseQrContent("WIFI:T:WPA;S:xxx;P:xxx;;")
        assertEquals(QrType.WIFI, c.type)
        assertEquals("xxx", c.display)
        assertEquals("WPA", c.wifi!!.security)
        assertEquals("xxx", c.wifi!!.password)
    }

    @Test
    fun wifiEscapedSsid() {
        val c = parseQrContent("WIFI:S:Cafe\\;Bar;T:WPA;P:p\\\\w;H:true;;")
        assertEquals(QrType.WIFI, c.type)
        assertEquals("Cafe;Bar", c.display)
        assertEquals("p\\w", c.wifi!!.password)
        assertTrue(c.wifi!!.hidden)
    }

    @Test
    fun wifiNoPassword() {
        val c = parseQrContent("WIFI:T:nopass;S:Home_WiFi;;")
        assertEquals(QrType.WIFI, c.type)
        assertEquals("无", c.wifi!!.security)
        assertNull(c.wifi!!.password)
    }

    @Test
    fun sms() {
        val c = parseQrContent("smsto:10086?body=hello")
        assertEquals(QrType.SMS, c.type)
        assertEquals("10086", c.display)
        assertEquals("hello", c.detail)
    }

    @Test
    fun appScheme() {
        val c = parseQrContent("weixin://dl/scan")
        assertEquals(QrType.APP, c.type)
    }

    @Test
    fun unknownScheme() {
        val c = parseQrContent("myapp-unknown://open?id=1")
        assertEquals(QrType.APP, c.type)
    }

    @Test
    fun javascriptBlocked() {
        assertEquals(QrType.UNKNOWN, parseQrContent("javascript:alert(1)").type)
    }

    @Test
    fun dataUriBlocked() {
        assertEquals(QrType.UNKNOWN, parseQrContent("data:text/html,<h1>x</h1>").type)
    }

    @Test
    fun fileUriBlocked() {
        assertEquals(QrType.UNKNOWN, parseQrContent("file:///etc/passwd").type)
    }

    @Test
    fun intentUriBlocked() {
        assertEquals(
            QrType.UNKNOWN,
            parseQrContent("intent://scan/#Intent;scheme=zxing;end").type
        )
    }

    @Test
    fun invalidUrl() {
        assertEquals(QrType.UNKNOWN, parseQrContent("http://").type)
        assertEquals(QrType.UNKNOWN, parseQrContent("https://").type)
    }

    @Test
    fun invalidGeo() {
        assertEquals(QrType.UNKNOWN, parseQrContent("geo:abc").type)
        assertEquals(QrType.UNKNOWN, parseQrContent("geo:28.2282").type)
    }

    @Test
    fun invalidTel() {
        assertEquals(QrType.UNKNOWN, parseQrContent("tel:").type)
    }

    @Test
    fun emptyContent() {
        assertEquals(QrType.UNKNOWN, parseQrContent("").type)
        assertEquals(QrType.UNKNOWN, parseQrContent("   ").type)
    }

    @Test
    fun wifiWithoutSsidIsUnknown() {
        assertEquals(QrType.UNKNOWN, parseQrContent("WIFI:T:WPA;P:123456;;").type)
    }

    @Test
    fun veryLongTextDoesNotCrash() {
        val long = "A".repeat(200_000)
        val c = parseQrContent(long)
        assertEquals(QrType.TEXT, c.type)
        assertEquals(long, c.display)
    }

    @Test
    fun primaryActionOnlyForRoutableTypes() {
        assertTrue(QrActions.hasPrimaryAction(parseQrContent("https://example.com")))
        assertTrue(QrActions.hasPrimaryAction(parseQrContent("tel:10086")))
        assertTrue(QrActions.hasPrimaryAction(parseQrContent("smsto:10086?body=hi")))
        assertTrue(QrActions.hasPrimaryAction(parseQrContent("mailto:a@b.com")))
        assertTrue(QrActions.hasPrimaryAction(parseQrContent("geo:28.2282,112.9388")))
        assertTrue(QrActions.hasPrimaryAction(parseQrContent("weixin://dl/scan")))

        // 文本 / Wi-Fi / 未知类型不提供任何外跳按钮
        assertFalse(QrActions.hasPrimaryAction(parseQrContent("Hello World")))
        assertFalse(QrActions.hasPrimaryAction(parseQrContent("WIFI:T:WPA;S:xxx;P:xxx;;")))
        assertFalse(QrActions.hasPrimaryAction(parseQrContent("javascript:alert(1)")))
        assertFalse(QrActions.hasPrimaryAction(parseQrContent("http://")))
    }

    @Test
    fun actionsNeverBuildIntentForNonRoutableTypes() {
        assertNull(QrActions.buildIntent(parseQrContent("Hello World")))
        assertNull(QrActions.buildIntent(parseQrContent("WIFI:T:WPA;S:xxx;P:xxx;;")))
        assertNull(QrActions.buildIntent(parseQrContent("javascript:alert(1)")))
        assertNull(QrActions.buildIntent(parseQrContent("file:///etc/passwd")))
    }
}
