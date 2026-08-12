package com.gametodolist.domain

import com.gametodolist.data.entity.TaskTemplateEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 显示窗口（毫秒时间戳，[start, end)，end 即截止时间） */
data class TimeWindow(val startMillis: Long, val endMillis: Long)

/**
 * 循环规则计算引擎。纯函数，时区由调用方注入，便于单元测试。
 *
 * 循环语义：
 * - INTERVAL_DAYS：以模板创建日为锚，每 N 天一个循环
 * - WEEKLY：weekdaysMask 中被选中的每个星期几都是循环起点
 * - MONTHLY：每月 monthDay 日（短月钳制到月末）为循环起点
 *
 * 显示窗口：
 * - FULL_CYCLE：[本次循环起点 00:00, 下次循环起点 00:00)
 * - CUSTOM：[循环起点日 00:00, 循环起点日 + customWindowDays 天 00:00)，
 *   customWindowDays 最小为 1（1 = 显示 1 天，2 = 显示 2 天，依次类推）
 */
object RecurrenceEngine {

    private fun typeOf(t: TaskTemplateEntity): RecurrenceType =
        runCatching { RecurrenceType.valueOf(t.recurrenceType) }.getOrDefault(RecurrenceType.NONE)

    private fun isWeekdaySelected(date: LocalDate, mask: Int): Boolean =
        mask and (1 shl (date.dayOfWeek.value - 1)) != 0

    private fun clampMonthDay(day: Int, month: LocalDate): LocalDate =
        month.withDayOfMonth(day.coerceIn(1, month.lengthOfMonth()))

    /** 循环锚点日期：优先用户设置的起始日期，否则用创建时间 */
    private fun anchorDateOf(t: TaskTemplateEntity, zone: ZoneId): LocalDate {
        val millis = if (t.anchorDate > 0) t.anchorDate else t.createdAt
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    }

    /** 严格在 [cycleStart] 之后的下一个循环起点日期 */
    fun nextCycleStartDate(cycleStart: LocalDate, t: TaskTemplateEntity): LocalDate =
        when (typeOf(t)) {
            RecurrenceType.NONE -> cycleStart
            RecurrenceType.INTERVAL_DAYS ->
                cycleStart.plusDays(t.intervalDays.coerceAtLeast(1).toLong())
            RecurrenceType.WEEKLY -> {
                require(t.weekdaysMask != 0) { "WEEKLY 规则 weekdaysMask 不能为 0" }
                (1..7).asSequence()
                    .map { cycleStart.plusDays(it.toLong()) }
                    .first { isWeekdaySelected(it, t.weekdaysMask) }
            }
            RecurrenceType.MONTHLY -> {
                val nextMonth = cycleStart.plusMonths(1)
                clampMonthDay(t.monthDay, nextMonth)
            }
        }

    /** 包含 [date]（或其之前最近一次）的循环起点日期 */
    fun currentCycleStartDate(date: LocalDate, t: TaskTemplateEntity, zone: ZoneId): LocalDate =
        when (typeOf(t)) {
            RecurrenceType.NONE -> anchorDateOf(t, zone)
            RecurrenceType.INTERVAL_DAYS -> {
                val anchor = anchorDateOf(t, zone)
                val n = t.intervalDays.coerceAtLeast(1).toLong()
                val daysBetween = ChronoUnit.DAYS.between(anchor, date)
                if (daysBetween < 0) anchor else anchor.plusDays(daysBetween / n * n)
            }
            RecurrenceType.WEEKLY -> {
                require(t.weekdaysMask != 0) { "WEEKLY 规则 weekdaysMask 不能为 0" }
                (0..6).asSequence()
                    .map { date.minusDays(it.toLong()) }
                    .first { isWeekdaySelected(it, t.weekdaysMask) }
            }
            RecurrenceType.MONTHLY -> {
                val thisMonth = clampMonthDay(t.monthDay, date)
                if (thisMonth <= date) thisMonth
                else clampMonthDay(t.monthDay, date.minusMonths(1))
            }
        }

    /** 计算某个循环的显示窗口 */
    fun computeWindow(cycleStart: LocalDate, t: TaskTemplateEntity, zone: ZoneId): TimeWindow {
        val mode = runCatching { WindowMode.valueOf(t.windowMode) }.getOrDefault(WindowMode.FULL_CYCLE)
        // 不循环：FULL_CYCLE = 长期有效；CUSTOM = 从开始日期起持续 N 天，到期作废
        if (typeOf(t) == RecurrenceType.NONE) {
            val start = cycleStart.atStartOfDay(zone)
            if (mode == WindowMode.FULL_CYCLE) {
                return TimeWindow(start.toInstant().toEpochMilli(), Long.MAX_VALUE)
            }
            val days = t.customWindowDays.coerceAtLeast(1).toLong()
            return TimeWindow(
                start.toInstant().toEpochMilli(),
                cycleStart.plusDays(days).atStartOfDay(zone).toInstant().toEpochMilli()
            )
        }
        return when (mode) {
            WindowMode.FULL_CYCLE -> {
                val start = cycleStart.atStartOfDay(zone)
                val end = nextCycleStartDate(cycleStart, t).atStartOfDay(zone)
                TimeWindow(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
            }
            WindowMode.CUSTOM -> {
                val days = t.customWindowDays.coerceAtLeast(1).toLong()
                val start = cycleStart.atStartOfDay(zone)
                val end = cycleStart.plusDays(days).atStartOfDay(zone)
                TimeWindow(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
            }
        }
    }
}
