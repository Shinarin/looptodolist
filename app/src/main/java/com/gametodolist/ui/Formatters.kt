package com.gametodolist.ui

import com.gametodolist.data.entity.TaskTemplateEntity
import com.gametodolist.domain.RecurrenceType
import com.gametodolist.domain.toRecurrenceType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val zone: ZoneId get() = ZoneId.systemDefault()

fun Long.toDateText(): String =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
        .format(DateTimeFormatter.ofPattern("M月d日"))

fun Long.toDateTimeText(): String =
    Instant.ofEpochMilli(this).atZone(zone)
        .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))

fun Long.toFullDateText(): String =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
        .format(DateTimeFormatter.ofPattern("yyyy年M月d日"))

fun Long.toLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

fun LocalDate.toEpochMillis(): Long =
    atStartOfDay(zone).toInstant().toEpochMilli()

val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

fun TaskTemplateEntity.recurrenceLabel(): String =
    when (recurrenceType.toRecurrenceType()) {
        RecurrenceType.NONE -> "不循环"
        RecurrenceType.INTERVAL_DAYS -> "每 ${intervalDays} 天"
        RecurrenceType.WEEKLY -> {
            val days = (0..6).filter { weekdaysMask and (1 shl it) != 0 }
                .joinToString("、") { WEEKDAY_LABELS[it] }
            "每周$days"
        }
        RecurrenceType.MONTHLY -> "每月 ${monthDay} 日"
    }

/** 剩余时间展示文案；referenceMillis 为比较基准（默认现在，日历传所选日期） */
fun remainingLabel(
    windowStart: Long,
    windowEnd: Long,
    referenceMillis: Long = System.currentTimeMillis()
): String {
    if (windowEnd == Long.MAX_VALUE) return "长期有效"
    if (windowStart > referenceMillis) return "${windowStart.toDateText()} 开始"
    val refDay = Instant.ofEpochMilli(referenceMillis).atZone(zone).toLocalDate()
    val endDay = Instant.ofEpochMilli(windowEnd).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(refDay, endDay)
    return when {
        days < 0 -> "已截止"
        days == 0L -> "今日截止"
        days == 1L -> "明日截止"
        else -> "剩 $days 天"
    }
}
