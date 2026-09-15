package com.scan.qr

import com.scan.qr.ui.stampLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class VersionLabelTest {

    private fun millisOf(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.YEAR, y)
            set(Calendar.MONTH, mo - 1)
            set(Calendar.DAY_OF_MONTH, d)
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, mi)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun withoutInstallTimeFallsBackToVersionOnly() {
        assertEquals("v1.8", stampLabel("1.8", 0L))
    }

    @Test
    fun showsVersionAndInstallTime() {
        val t = millisOf(2026, 9, 15, 18, 43)
        assertEquals("v1.8 · 装于 09-15 18:43", stampLabel("1.8", t))
    }

    @Test
    fun oldInstallIsVisiblyOld() {
        // 旧包（例如 v1.2）会带着很旧的安装时间，一眼能看出没被替换
        val t = millisOf(2026, 9, 14, 16, 50)
        val label = stampLabel("1.2", t)
        assertTrue(label.startsWith("v1.2 · 装于 09-14"))
    }
}
