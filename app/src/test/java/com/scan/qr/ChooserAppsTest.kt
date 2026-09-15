package com.scan.qr

import com.scan.qr.scanner.AppChoice
import com.scan.qr.scanner.mergeChooserApps
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 应用内「选择其他应用打开」候选列表的合并规则。
 *
 * 背景：系统选择器在**只有一个候选**时会直接启动、不弹界面（AOSP ChooserActivity），
 * 而 ColorOS 对 http/https 只查得到系统浏览器一个 → 会静默变成「直接用浏览器打开」。
 * 于是结果页改成应用内自己列候选。这里锁住合并规则。
 */
class ChooserAppsTest {

    private val self = "com.scan.qr"
    private val heytap = AppChoice("com.heytap.browser", "系统浏览器")
    private val edge = AppChoice("com.microsoft.emmx", "Edge")
    private val taobao = AppChoice("com.taobao.taobao", "淘宝")

    @Test
    fun urlMergesResolvedWithKnownBrowsers() {
        val merged = mergeChooserApps(listOf(heytap), listOf(edge), isUrl = true, selfPackage = self)
        assertEquals(listOf("Edge", "系统浏览器"), merged.map { it.label })
    }

    @Test
    fun nonUrlDoesNotPullInBrowsers() {
        // 拨号/短信这类内容不该混进浏览器
        val merged = mergeChooserApps(listOf(taobao), listOf(edge), isUrl = false, selfPackage = self)
        assertEquals(listOf("淘宝"), merged.map { it.label })
    }

    @Test
    fun resolvedResultWinsOverBuiltInLabel() {
        // 同一个包两边都有时，保留系统查询给的名字（更准），且不重复
        val resolved = AppChoice("com.heytap.browser", "浏览器")
        val merged = mergeChooserApps(listOf(resolved), listOf(heytap), isUrl = true, selfPackage = self)
        assertEquals(1, merged.size)
        assertEquals("浏览器", merged[0].label)
    }

    @Test
    fun selfPackageIsExcluded() {
        val merged = mergeChooserApps(
            listOf(AppChoice(self, "ScanLite"), heytap), emptyList(), isUrl = true, selfPackage = self
        )
        assertEquals(listOf("系统浏览器"), merged.map { it.label })
    }

    @Test
    fun blankPackagesAreDropped() {
        val merged = mergeChooserApps(
            listOf(AppChoice("", "无包名")), emptyList(), isUrl = false, selfPackage = self
        )
        assertEquals(emptyList<AppChoice>(), merged)
    }
}
