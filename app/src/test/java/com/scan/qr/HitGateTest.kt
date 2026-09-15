package com.scan.qr

import com.scan.qr.scanner.BarcodeHit
import com.scan.qr.scanner.GateResult
import com.scan.qr.scanner.HitGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HitGateTest {

    private fun hit(raw: String, left: Float = 0f) = BarcodeHit(
        raw = raw,
        left = left,
        top = 0f,
        right = left + 100f,
        bottom = 100f,
        contentWidth = 1440,
        contentHeight = 1080
    )

    private fun gate() = HitGate(
        stableMs = 450L,
        multiStableMs = 200L,
        repositionMs = 300L,
        multiGraceMs = 600L
    )

    private fun two(off: Float = 0f) = listOf(hit("A", off), hit("B", 200f + off))

    @Test
    fun noHitIsIgnored() {
        assertEquals(GateResult.Ignore, gate().onHits(emptyList(), 1000L, 1440, 1080))
    }

    @Test
    fun singleHitNeedsStableTime() {
        val g = gate()
        assertEquals(GateResult.Ignore, g.onHits(listOf(hit("A")), 1000L, 1440, 1080))
        assertEquals(GateResult.Ignore, g.onHits(listOf(hit("A")), 1200L, 1440, 1080))
        val r = g.onHits(listOf(hit("A")), 1460L, 1440, 1080)
        assertTrue(r is GateResult.Single)
        assertEquals("A", (r as GateResult.Single).raw)
    }

    @Test
    fun jitterDoesNotJumpToResult() {
        val g = gate()
        assertEquals(GateResult.Ignore, g.onHits(listOf(hit("A")), 0L, 1440, 1080))
        // 200ms 后画面变多码 → 绝不能直接进结果页
        assertTrue(g.onHits(two(), 200L, 1440, 1080) !is GateResult.Single)
    }

    @Test
    fun multiAppearsOnlyAfterStabilityWindow() {
        val g = gate()
        assertEquals(GateResult.Ignore, g.onHits(two(), 5_000L, 1440, 1080))
        // 未满 200ms 不显示浮层（避免镜头扫过时闪一下）
        assertEquals(GateResult.Ignore, g.onHits(two(), 5_100L, 1440, 1080))
        val r = g.onHits(two(), 5_200L, 1440, 1080)
        assertTrue(r is GateResult.Multi)
        val multi = r as GateResult.Multi
        assertEquals(listOf("A", "B"), multi.hits.map { it.raw })
        assertEquals(1440, multi.contentWidth)
        assertEquals(1080, multi.contentHeight)
    }

    @Test
    fun stableMultiIsNotRedrawnEveryFrame() {
        val g = gate()
        g.onHits(two(), 5_000L, 1440, 1080)
        assertTrue(g.onHits(two(), 5_200L, 1440, 1080) is GateResult.Multi)
        // 同一组二维码、位置微动 → 不重画（消除浮层抖动）
        assertEquals(GateResult.Ignore, g.onHits(two(4f), 5_300L, 1440, 1080))
        assertEquals(GateResult.Ignore, g.onHits(two(8f), 5_400L, 1440, 1080))
        // 超过位置跟随间隔后才更新一次
        assertTrue(g.onHits(two(12f), 5_500L, 1440, 1080) is GateResult.Multi)
    }

    @Test
    fun contentChangeUpdatesImmediately() {
        val g = gate()
        g.onHits(two(), 5_000L, 1440, 1080)
        assertTrue(g.onHits(two(), 5_200L, 1440, 1080) is GateResult.Multi)
        // 换成另一组二维码（码数变多）→ 立即更新
        val r = g.onHits(listOf(hit("A"), hit("C", 200f), hit("D", 400f)), 5_250L, 1440, 1080)
        assertTrue(r is GateResult.Multi)
        assertEquals(listOf("A", "C", "D"), (r as GateResult.Multi).hits.map { it.raw })
    }

    @Test
    fun transientDropFrameDoesNotFlickerOverlay() {
        val g = gate()
        g.onHits(two(), 5_000L, 1440, 1080)
        assertTrue(g.onHits(two(), 5_200L, 1440, 1080) is GateResult.Multi)
        // 某帧只识别到一个码：宽限期内不收起浮层（这是多码闪烁的根因）
        assertEquals(GateResult.Ignore, g.onHits(listOf(hit("A")), 5_300L, 1440, 1080))
        // 仍在宽限期：继续不闪
        assertEquals(GateResult.Ignore, g.onHits(listOf(hit("A")), 5_600L, 1440, 1080))
        // 两个码又回来了：浮层保持，且到点跟随位置
        assertTrue(g.onHits(two(), 5_700L, 1440, 1080) is GateResult.Multi)
    }

    @Test
    fun multiDegradingToSingleClearsAfterGraceThenScans() {
        val g = gate()
        g.onHits(two(), 5_000L, 1440, 1080)
        g.onHits(two(), 5_200L, 1440, 1080)
        // 只剩一个码：宽限期内先不动
        assertEquals(GateResult.Ignore, g.onHits(listOf(hit("A")), 5_300L, 1440, 1080))
        // 宽限期结束仍只有一个码 → 收起浮层，回到单码流程
        assertEquals(GateResult.Clear, g.onHits(listOf(hit("A")), 5_950L, 1440, 1080))
        // 单码重新计时后进结果页
        val r = g.onHits(listOf(hit("A")), 6_450L, 1440, 1080)
        assertTrue(r is GateResult.Single)
        assertEquals("A", (r as GateResult.Single).raw)
    }

    @Test
    fun leavingFrameClearsOverlayAfterGrace() {
        val g = gate()
        g.onHits(two(), 5_000L, 1440, 1080)
        g.onHits(two(), 5_200L, 1440, 1080)
        // 一帧没识别到：宽限期内不闪
        assertEquals(GateResult.Ignore, g.onHits(emptyList(), 5_300L, 1440, 1080))
        // 宽限期结束 → 清浮层
        assertEquals(GateResult.Clear, g.onHits(emptyList(), 6_100L, 1440, 1080))
        assertEquals(GateResult.Ignore, g.onHits(emptyList(), 6_300L, 1440, 1080))
    }

    @Test
    fun resetStartsOver() {
        val g = gate()
        g.onHits(listOf(hit("A")), 0L, 1440, 1080)
        g.reset()
        assertEquals(GateResult.Ignore, g.onHits(listOf(hit("A")), 1000L, 1440, 1080))
        assertTrue(g.onHits(listOf(hit("A")), 1500L, 1440, 1080) is GateResult.Single)
    }
}
