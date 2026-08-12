package com.gametodolist.domain

/** 循环类型 */
enum class RecurrenceType { NONE, INTERVAL_DAYS, WEEKLY, MONTHLY }

/** 显示时段模式 */
enum class WindowMode { FULL_CYCLE, CUSTOM }

/** 任务实例状态 */
enum class InstanceStatus { ACTIVE, COMPLETED, EXPIRED }

/** 预设分类色板（柔和配色） */
val CATEGORY_COLOR_PRESETS: List<Long> = listOf(
    0xFF7FB69E, // 抹茶绿
    0xFF8EA7E9, // 雾蓝
    0xFFE9A2B2, // 樱粉
    0xFFF0B67F, // 杏橙
    0xFFB39DDB, // 薰衣草紫
    0xFF80CBC4, // 青碧
    0xFFE6C97A, // 米黄
    0xFF9E9E9E  // 暖灰
)
