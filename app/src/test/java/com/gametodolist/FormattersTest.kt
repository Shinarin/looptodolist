package com.gametodolist

import com.gametodolist.ui.cooldownLabel
import com.gametodolist.ui.remainingDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class FormattersTest {
    private val zone: ZoneId = ZoneId.systemDefault()
    private fun LocalDate.dayMillis() = atStartOfDay(zone).toInstant().toEpochMilli()
    private val today: LocalDate = LocalDate.now()

    @Test
    fun `长期有效返回 null`() {
        assertNull(remainingDays(today.dayMillis(), Long.MAX_VALUE))
    }

    @Test
    fun `尚未开始返回 null`() {
        assertNull(remainingDays(today.plusDays(3).dayMillis(), today.plusDays(10).dayMillis()))
    }

    @Test
    fun `今日截止返回 0`() {
        assertEquals(0L, remainingDays(today.dayMillis(), today.dayMillis()))
    }

    @Test
    fun `剩 N 天返回 N`() {
        assertEquals(2L, remainingDays(today.dayMillis(), today.plusDays(2).dayMillis()))
        assertEquals(6L, remainingDays(today.dayMillis(), today.plusDays(6).dayMillis()))
    }

    @Test
    fun `已截止返回负数`() {
        assertEquals(-1L, remainingDays(today.dayMillis(), today.minusDays(1).dayMillis()))
    }

    @Test
    fun `冷却恢复倒计时文案`() {
        assertNull(cooldownLabel(Long.MAX_VALUE))
        assertEquals("今日恢复", cooldownLabel(today.dayMillis()))
        assertEquals("明日恢复", cooldownLabel(today.plusDays(1).dayMillis()))
        assertEquals("剩 5 天恢复", cooldownLabel(today.plusDays(5).dayMillis()))
        // 恢复日已过（结算尚未跑）也按「今日恢复」显示
        assertEquals("今日恢复", cooldownLabel(today.minusDays(1).dayMillis()))
    }
}
