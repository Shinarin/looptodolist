package com.gametodolist

import com.gametodolist.data.entity.TaskTemplateEntity
import com.gametodolist.domain.RecurrenceEngine
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class RecurrenceEngineTest {

    private val zone: ZoneId = ZoneOffset.UTC

    private fun template(
        type: String,
        createdAt: Long = LocalDate.of(2025, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli(),
        intervalDays: Int = 1,
        weekdaysMask: Int = 0,
        monthDay: Int = 1,
        anchorDate: Long = 0,
        windowMode: String = "FULL_CYCLE",
        customWindowDays: Int = 0
    ) = TaskTemplateEntity(
        id = 1,
        categoryId = null,
        title = "t",
        recurrenceType = type,
        intervalDays = intervalDays,
        weekdaysMask = weekdaysMask,
        monthDay = monthDay,
        anchorDate = anchorDate,
        windowMode = windowMode,
        customWindowDays = customWindowDays,
        createdAt = createdAt
    )

    private fun millisOf(date: LocalDate, hour: Int = 0, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    // 2025-01-06 是周一，2025-01-08 是周三
    private val monday = LocalDate.of(2025, 1, 6)
    private val wednesday = LocalDate.of(2025, 1, 8)

    // ---------- WEEKLY ----------

    @Test
    fun `weekly 下一个循环起点`() {
        val t = template("WEEKLY", weekdaysMask = 0b0000101) // 周一 + 周三
        assertEquals(wednesday, RecurrenceEngine.nextCycleStartDate(monday, t))
        assertEquals(LocalDate.of(2025, 1, 13), RecurrenceEngine.nextCycleStartDate(wednesday, t))
    }

    @Test
    fun `weekly 当前循环起点`() {
        val t = template("WEEKLY", weekdaysMask = 0b0000101)
        assertEquals(monday, RecurrenceEngine.currentCycleStartDate(LocalDate.of(2025, 1, 7), t, zone))
        assertEquals(wednesday, RecurrenceEngine.currentCycleStartDate(LocalDate.of(2025, 1, 8), t, zone))
    }

    @Test
    fun `weekly 整循环窗口`() {
        val t = template("WEEKLY", weekdaysMask = 0b0000101)
        val window = RecurrenceEngine.computeWindow(monday, t, zone)
        assertEquals(millisOf(monday), window.startMillis)
        assertEquals(millisOf(wednesday), window.endMillis)
    }

    // ---------- MONTHLY ----------

    @Test
    fun `monthly 短月钳制到月末`() {
        val t = template("MONTHLY", monthDay = 31)
        assertEquals(
            LocalDate.of(2025, 2, 28),
            RecurrenceEngine.nextCycleStartDate(LocalDate.of(2025, 1, 31), t)
        )
    }

    @Test
    fun `monthly 当前循环起点取上一锚点`() {
        val t = template("MONTHLY", monthDay = 31)
        assertEquals(
            LocalDate.of(2025, 2, 28),
            RecurrenceEngine.currentCycleStartDate(LocalDate.of(2025, 3, 15), t, zone)
        )
        assertEquals(
            LocalDate.of(2025, 3, 31),
            RecurrenceEngine.currentCycleStartDate(LocalDate.of(2025, 3, 31), t, zone)
        )
    }

    // ---------- INTERVAL_DAYS ----------

    @Test
    fun `interval 当前与下一循环起点`() {
        val t = template("INTERVAL_DAYS", intervalDays = 3)
        val day10 = LocalDate.of(2025, 1, 10)
        assertEquals(day10, RecurrenceEngine.currentCycleStartDate(day10, t, zone))
        assertEquals(LocalDate.of(2025, 1, 13), RecurrenceEngine.nextCycleStartDate(day10, t))
        // 锚点 1-01，1-09 距离 8 天，8/3*3=6 → 1-07
        assertEquals(
            LocalDate.of(2025, 1, 7),
            RecurrenceEngine.currentCycleStartDate(LocalDate.of(2025, 1, 9), t, zone)
        )
    }

    // ---------- 起始日期 anchorDate ----------

    @Test
    fun `interval 使用自定义起始日期作为锚点`() {
        val anchor = LocalDate.of(2025, 2, 1)
        val t = template(
            "INTERVAL_DAYS",
            intervalDays = 3,
            anchorDate = anchor.atStartOfDay(zone).toInstant().toEpochMilli()
        )
        val day10 = LocalDate.of(2025, 2, 10) // 距锚点 9 天 → 恰为循环日
        assertEquals(day10, RecurrenceEngine.currentCycleStartDate(day10, t, zone))
        assertEquals(
            LocalDate.of(2025, 2, 7),
            RecurrenceEngine.currentCycleStartDate(LocalDate.of(2025, 2, 9), t, zone)
        )
    }

    @Test
    fun `none 使用自定义起始日期`() {
        val anchor = LocalDate.of(2025, 3, 15)
        val t = template(
            "NONE",
            anchorDate = anchor.atStartOfDay(zone).toInstant().toEpochMilli()
        )
        assertEquals(anchor, RecurrenceEngine.currentCycleStartDate(LocalDate.of(2025, 3, 20), t, zone))
    }

    // ---------- CUSTOM 窗口 ----------

    @Test
    fun `custom 窗口按显示天数计算`() {
        val t1 = template("INTERVAL_DAYS", intervalDays = 7, windowMode = "CUSTOM", customWindowDays = 1)
        val window1 = RecurrenceEngine.computeWindow(monday, t1, zone)
        assertEquals(millisOf(monday), window1.startMillis)
        assertEquals(millisOf(monday.plusDays(1)), window1.endMillis)

        val t3 = template("INTERVAL_DAYS", intervalDays = 7, windowMode = "CUSTOM", customWindowDays = 3)
        val window3 = RecurrenceEngine.computeWindow(monday, t3, zone)
        assertEquals(millisOf(monday), window3.startMillis)
        assertEquals(millisOf(monday.plusDays(3)), window3.endMillis)
    }

    @Test
    fun `custom 显示天数为 0 时按 1 天处理`() {
        val t = template("INTERVAL_DAYS", intervalDays = 7, windowMode = "CUSTOM", customWindowDays = 0)
        val window = RecurrenceEngine.computeWindow(monday, t, zone)
        assertEquals(millisOf(monday), window.startMillis)
        assertEquals(millisOf(monday.plusDays(1)), window.endMillis)
    }

    // ---------- 不循环的持续天数 ----------

    @Test
    fun `none 整循环即长期有效`() {
        val t = template("NONE", windowMode = "FULL_CYCLE")
        val window = RecurrenceEngine.computeWindow(monday, t, zone)
        assertEquals(millisOf(monday), window.startMillis)
        assertEquals(Long.MAX_VALUE, window.endMillis)
    }

    @Test
    fun `none 自定义持续天数`() {
        val t = template("NONE", windowMode = "CUSTOM", customWindowDays = 3)
        val window = RecurrenceEngine.computeWindow(monday, t, zone)
        assertEquals(millisOf(monday), window.startMillis)
        assertEquals(millisOf(monday.plusDays(3)), window.endMillis)
    }
}
