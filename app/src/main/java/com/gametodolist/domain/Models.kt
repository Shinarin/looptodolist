package com.gametodolist.domain

/** 循环类型 */
enum class RecurrenceType { NONE, INTERVAL_DAYS, WEEKLY, MONTHLY }

/** 显示时段模式 */
enum class WindowMode { FULL_CYCLE, CUSTOM }

/** 任务实例状态 */
enum class InstanceStatus { ACTIVE, COMPLETED, EXPIRED }

/** 数据库字符串 → 循环类型；非法/空值安全降级为 NONE */
fun String?.toRecurrenceType(): RecurrenceType =
    runCatching { RecurrenceType.valueOf(this.orEmpty()) }.getOrDefault(RecurrenceType.NONE)

/** 数据库字符串 → 显示时段模式；非法/空值安全降级为 FULL_CYCLE */
fun String?.toWindowMode(): WindowMode =
    runCatching { WindowMode.valueOf(this.orEmpty()) }.getOrDefault(WindowMode.FULL_CYCLE)

/** 预设分类色板（图鉴色系） */
val CATEGORY_COLOR_PRESETS: List<Long> = listOf(
    0xFF5B8DEF, // 图鉴蓝
    0xFFFF7BAC, // 糖果粉
    0xFFF7B733, // 金币黄
    0xFF45C48A, // 通关绿
    0xFF9D6CFF, // 魔法紫
    0xFF4ECDC4, // 水晶青
    0xFFFF9A5C, // 活力橙
    0xFF9E9E9E  // 暖灰
)
