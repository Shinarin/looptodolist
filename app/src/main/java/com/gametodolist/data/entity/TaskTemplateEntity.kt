package com.gametodolist.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 清单项模板：保存循环规则与显示时段配置。
 * recurrenceType: NONE / INTERVAL_DAYS / WEEKLY / MONTHLY
 * windowMode: FULL_CYCLE（覆盖本次循环到下次循环）/ CUSTOM（从循环起点起显示 N 天）
 */
@Entity(tableName = "task_templates", indices = [Index("categoryId")])
data class TaskTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long?,
    val title: String,
    val note: String = "",
    // 预留：任务排序未启用（恒为 0，按 id 排序）
    val sortOrder: Int = 0,

    val recurrenceType: String = "NONE",
    val intervalDays: Int = 1,      // INTERVAL_DAYS：间隔天数
    val weekdaysMask: Int = 0,      // WEEKLY：星期位掩码，bit0=周一 … bit6=周日
    val monthDay: Int = 1,          // MONTHLY：每月第几日（短月自动钳制到月末）

    val windowMode: String = "FULL_CYCLE",
    val customWindowDays: Int = 1,        // CUSTOM：从循环起点起显示的天数（1 = 显示 1 天，最小 1）
    @Deprecated("CUSTOM 已简化为按天数，不再使用时刻") val customStartMinutes: Int = 0,
    @Deprecated("CUSTOM 已简化为按天数，不再使用时刻") val customEndMinutes: Int = 1439,

    val reminderEnabled: Boolean = false,
    val reminderMinutesBefore: Int = 60,

    /**
     * 起始日期（本地零点毫秒，0 表示未设置）：
     * INTERVAL_DAYS 的循环锚点；NONE 一次性任务的日期。默认取创建时间。
     */
    val anchorDate: Long = 0,

    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val paused: Boolean = false
)
