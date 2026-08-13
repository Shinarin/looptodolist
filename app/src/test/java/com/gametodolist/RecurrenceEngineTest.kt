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

    // ---------- WEEKLY/MONTHLY 起始日期 ----------

    @Test
    fun `weekly 起始日期在未来 首个循环不早于起始日期`() {
        // mask=周一，anchor=2025-01-08 周三 → 首个循环 = 下周一 1-13
        val anchor = LocalDate.of(2025, 1, 8)
        val t = template(
            "WEEKLY", weekdaysMask = 0b0000001,
            anchorDate = anchor.atStartOfDay(zone).toInstant().toEpochMilli()
        )
        // 查询 anchor 当天（周三）：正常回推得 1-06，但被钳到首个循环 1-13
        assertEquals(LocalDate.of(2025, 1, 13), RecurrenceEngine.currentCycleStartDate(anchor, t, zone))
        // 查询 1-20：正常回推 1-20（周一），≥ first，不受影响
        assertEquals(LocalDate.of(2025, 1, 20), RecurrenceEngine.currentCycleStartDate(LocalDate.of(2025, 1, 20), t, zone))
    }

    @Test
    fun `weekly 起始日期在过去 行为与未设置一致`() {
        val anchor = LocalDate.of(2024, 12, 1)
        val t = template(
            "WEEKLY", weekdaysMask = 0b0000101,
            anchorDate = anchor.atStartOfDay(zone).toInstant().toEpochMilli()
        )
        assertEquals(monday, RecurrenceEngine.currentCycleStartDate(LocalDate.of(2025, 1, 7), t, zone))
    }

    @Test
    fun `monthly 起始日期约束首个循环`() {
        // monthDay=15，anchor=2025-03-20 → 3-15 已过，首个循环 = 4-15
        val anchor = LocalDate.of(2025, 3, 20)
        val t = template(
            "MONTHLY", monthDay = 15,
            anchorDate = anchor.atStartOfDay(zone).toInstant().toEpochMilli()
        )
        assertEquals(LocalDate.of(2025, 4, 15), RecurrenceEngine.currentCycleStartDate(anchor, t, zone))
        // 5 月后正常
        assertEquals(LocalDate.of(2025, 5, 15), RecurrenceEngine.currentCycleStartDate(LocalDate.of(2025, 5, 20), t, zone))
    }

    @Test
    fun `monthly 起始日期与月末钳制`() {
        // monthDay=31，anchor=2025-02-10（非闰年）→ 首个循环 = 2-28
        val anchor = LocalDate.of(2025, 2, 10)
        val t = template(
            "MONTHLY", monthDay = 31,
            anchorDate = anchor.atStartOfDay(zone).toInstant().toEpochMilli()
        )
        assertEquals(LocalDate.of(2025, 2, 28), RecurrenceEngine.currentCycleStartDate(anchor, t, zone))
    }

    @Test
    fun `DST 时区窗口换算日期正确`() {
        val ny = ZoneId.of("America/New_York")
        // 2025-03-09 美国进入夏令时；窗口跨 3-08 → 3-10 两天
        val t = template("NONE", windowMode = "CUSTOM", customWindowDays = 2,
            anchorDate = LocalDate.of(2025, 3, 8).atStartOfDay(ny).toInstant().toEpochMilli())
        val cycle = RecurrenceEngine.currentCycleStartDate(LocalDate.of(2025, 3, 8), t, ny)
        val window = RecurrenceEngine.computeWindow(cycle, t, ny)
        val startDate = java.time.Instant.ofEpochMilli(window.startMillis).atZone(ny).toLocalDate()
        val endDate = java.time.Instant.ofEpochMilli(window.endMillis).atZone(ny).toLocalDate()
        assertEquals(LocalDate.of(2025, 3, 8), startDate)
        assertEquals(LocalDate.of(2025, 3, 10), endDate)
    }
}
