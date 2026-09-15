package com.scan.qr

import com.scan.qr.ui.overlay.ArrowDirection
import com.scan.qr.ui.overlay.RectF4
import com.scan.qr.ui.overlay.cropTransform
import com.scan.qr.ui.overlay.fitTransform
import com.scan.qr.ui.overlay.placeArrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayLayoutTest {

    private val viewW = 1080f
    private val viewH = 1920f

    @Test
    fun fitTransformCentersContentWithLetterbox() {
        // 内容 2000x1000（横图）显示在竖屏里 → 上下留黑边
        val t = fitTransform(2000, 1000, viewW, viewH)
        assertEquals(0.54f, t.scale, 0.001f)
        assertEquals(0f, t.offsetX, 0.01f)
        assertEquals((1920f - 1000f * 0.54f) / 2f, t.offsetY, 0.01f)
    }

    @Test
    fun cropTransformFillsViewAndCropsSides() {
        // 内容 2000x1000 铺满竖屏 → 左右被裁掉，scale 由高度决定
        val t = cropTransform(2000, 1000, viewW, viewH)
        assertEquals(1.92f, t.scale, 0.001f)
        assertTrue(t.offsetX < 0f)
        assertEquals(0f, t.offsetY, 0.01f)
    }

    @Test
    fun mappedBoxStaysAtItsRealPosition() {
        // 相册图 2000x1000，二维码在图片左下角 → 映射后仍应在视图左下区域
        val t = fitTransform(2000, 1000, viewW, viewH)
        val qr = RectF4(0f, 800f, 200f, 1000f)
        val mapped = t.map(qr)
        assertTrue("左侧", mapped.centerX() < viewW / 2)
        assertTrue("下方", mapped.centerY() > viewH / 2)
    }

    @Test
    fun arrowPointsTowardItsOwnQr() {
        val box = RectF4(400f, 800f, 600f, 1000f)
        val placements = placeArrows(listOf(box), viewW, viewH, arrowSize = 120f, gap = 12f)
        assertEquals(1, placements.size)
        val p = placements.first()
        // 首选放在右侧，箭头必须指向左（即指向二维码）
        assertEquals(ArrowDirection.LEFT, p.direction)
        assertTrue(p.rect.left >= box.right)
    }

    @Test
    fun arrowStaysInsideScreenForEdgeQr() {
        // 二维码贴着右边缘 → 箭头不能超出屏幕
        val box = RectF4(viewW - 120f, 400f, viewW, 600f)
        val placements = placeArrows(listOf(box), viewW, viewH, arrowSize = 120f, gap = 12f)
        val rect = placements.first().rect
        assertTrue(rect.left >= 0f)
        assertTrue(rect.right <= viewW)
        assertTrue(rect.top >= 0f)
        assertTrue(rect.bottom <= viewH)
    }

    @Test
    fun arrowsDoNotOverlapEachOther() {
        // 四个二维码分散在四角
        val boxes = listOf(
            RectF4(80f, 200f, 320f, 440f),
            RectF4(760f, 220f, 1000f, 460f),
            RectF4(100f, 1400f, 340f, 1640f),
            RectF4(740f, 1420f, 980f, 1660f)
        )
        val placements = placeArrows(boxes, viewW, viewH, arrowSize = 120f, gap = 12f)
        assertEquals(4, placements.size)
        placements.forEach { p ->
            assertTrue("箭头应在屏幕内", p.rect.left >= 0f && p.rect.right <= viewW)
            assertTrue("箭头应在屏幕内", p.rect.top >= 0f && p.rect.bottom <= viewH)
        }
        for (i in placements.indices) {
            for (j in i + 1 until placements.size) {
                assertTrue(
                    "箭头不能重叠",
                    !placements[i].rect.overlaps(placements[j].rect, 2f)
                )
            }
        }
    }

    @Test
    fun arrowsForTightlyPackedQrDoNotOverlap() {
        // 两个挨得很近的二维码
        val boxes = listOf(
            RectF4(300f, 900f, 480f, 1080f),
            RectF4(500f, 900f, 680f, 1080f)
        )
        val placements = placeArrows(boxes, viewW, viewH, arrowSize = 120f, gap = 8f)
        assertEquals(2, placements.size)
        assertTrue(!placements[0].rect.overlaps(placements[1].rect, 2f))
        placements.forEachIndexed { index, p ->
            assertTrue("箭头不能压住二维码框", !p.rect.overlaps(boxes[index], 2f))
        }
    }

    @Test
    fun everyQrGetsExactlyOneArrowAtItsOwnIndex() {
        val boxes = listOf(
            RectF4(100f, 100f, 300f, 300f),
            RectF4(600f, 700f, 800f, 900f),
            RectF4(200f, 1500f, 400f, 1700f)
        )
        val placements = placeArrows(boxes, viewW, viewH, arrowSize = 120f, gap = 12f)
        assertEquals(listOf(0, 1, 2), placements.map { it.index })
    }
}
