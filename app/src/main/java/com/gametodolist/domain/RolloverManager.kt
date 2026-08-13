package com.gametodolist.domain

import com.gametodolist.data.dao.TaskInstanceDao
import com.gametodolist.data.dao.TaskTemplateDao
import com.gametodolist.data.entity.TaskInstanceEntity
import com.gametodolist.data.entity.TaskTemplateEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 循环结算：把到期未完成的任务实例作废，并按规则生成下一个新实例。
 * App 启动、回前台、WorkManager 周期任务、小组件刷新时调用 [rolloverAll]。
 */
@Singleton
class RolloverManager @Inject constructor(
    private val templateDao: TaskTemplateDao,
    private val instanceDao: TaskInstanceDao
) {

    suspend fun rolloverAll(
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ) {
        templateDao.getActive().forEach { rolloverTemplate(it, nowMillis, zone) }
    }

    /** 对单个模板结算：保证存在一条当前有效的 ACTIVE 实例 */
    suspend fun rolloverTemplate(
        template: TaskTemplateEntity,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ) {
        val type = template.recurrenceType.toRecurrenceType()

        if (type == RecurrenceType.NONE) {
            // 不循环：保证存在一条一次性实例；设了持续天数的到期后自动作废，不再生成新实例
            val latest = instanceDao.getLatestForTemplate(template.id)
            if (latest == null) {
                val anchor = RecurrenceEngine.currentCycleStartDate(
                    Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate(), template, zone
                )
                insertInstance(template, anchor, zone)
            } else if (latest.status == InstanceStatus.ACTIVE.name &&
                latest.windowEnd != Long.MAX_VALUE && latest.windowEnd <= nowMillis
            ) {
                instanceDao.markExpired(latest.id)
            }
            return
        }

        var latest: TaskInstanceEntity = instanceDao.getLatestForTemplate(template.id)
            ?: insertInstance(
                template,
                RecurrenceEngine.currentCycleStartDate(
                    Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate(), template, zone
                ),
                zone
            )

        // 窗口已过：作废（若未完成）并逐循环补齐，直到当前窗口有效
        var guard = 0
        while (latest.windowEnd <= nowMillis && guard++ < 500) {
            if (latest.status == InstanceStatus.ACTIVE.name) {
                instanceDao.markExpired(latest.id)
            }
            val lastCycle = Instant.ofEpochMilli(latest.cycleStart).atZone(zone).toLocalDate()
            latest = insertInstance(template, RecurrenceEngine.nextCycleStartDate(lastCycle, template), zone)
        }
    }

    /** 模板规则变更后重算 ACTIVE 实例的窗口 */
    suspend fun regenerateActiveInstance(
        template: TaskTemplateEntity,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ) {
        val latest = instanceDao.getLatestForTemplate(template.id) ?: return
        if (latest.status != InstanceStatus.ACTIVE.name) return
        if (template.recurrenceType.toRecurrenceType() == RecurrenceType.NONE) {
            val anchor = RecurrenceEngine.currentCycleStartDate(
                Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate(), template, zone
            )
            val window = RecurrenceEngine.computeWindow(anchor, template, zone)
            instanceDao.update(
                latest.copy(
                    cycleStart = anchor.atStartOfDay(zone).toInstant().toEpochMilli(),
                    windowStart = window.startMillis,
                    windowEnd = window.endMillis
                )
            )
            return
        }
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val cycle = RecurrenceEngine.currentCycleStartDate(today, template, zone)
        val window = RecurrenceEngine.computeWindow(cycle, template, zone)
        instanceDao.update(
            latest.copy(
                cycleStart = cycle.atStartOfDay(zone).toInstant().toEpochMilli(),
                windowStart = window.startMillis,
                windowEnd = window.endMillis
            )
        )
        rolloverTemplate(template, nowMillis, zone)
    }

    private suspend fun insertInstance(
        template: TaskTemplateEntity,
        cycle: LocalDate,
        zone: ZoneId
    ): TaskInstanceEntity {
        val window = RecurrenceEngine.computeWindow(cycle, template, zone)
        val entity = TaskInstanceEntity(
            templateId = template.id,
            cycleStart = cycle.atStartOfDay(zone).toInstant().toEpochMilli(),
            windowStart = window.startMillis,
            windowEnd = window.endMillis
        )
        val id = instanceDao.insert(entity)
        return entity.copy(id = id)
    }
}
