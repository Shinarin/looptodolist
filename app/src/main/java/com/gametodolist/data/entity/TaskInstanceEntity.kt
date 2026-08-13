package com.gametodolist.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 任务实例：模板每个循环生成一条。
 * status: ACTIVE / COMPLETED / EXPIRED（到期未完成自动作废）
 */
@Entity(
    tableName = "task_instances",
    foreignKeys = [
        ForeignKey(
            entity = TaskTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("templateId"), Index("windowEnd"), Index("status"),
        Index(value = ["templateId", "cycleStart"], unique = true)
    ]
)
data class TaskInstanceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val templateId: Long,
    val cycleStart: Long,   // 本次循环起点 epoch millis
    val windowStart: Long,  // 显示窗口起点
    val windowEnd: Long,    // 显示窗口终点 = 截止时间
    val status: String = "ACTIVE",
    val completedAt: Long? = null
)
