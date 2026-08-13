package com.gametodolist.domain

import com.gametodolist.data.dao.TaskInstanceDao
import com.gametodolist.data.dao.TaskTemplateDao
import com.gametodolist.data.entity.TaskInstanceEntity
import com.gametodolist.data.entity.TaskTemplateEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 循环结算：把到期未完成的任务实例作废，并按规则生成下一个新实例。
 *
 * 统一入口语义：
 * - [rolloverAll]：App 启动、回前台、WorkManager 周期任务、数据变更管线调用。
 * - [regenerateActiveInstance]：模板规则变更（编辑保存）后调用——先就地修正当前实例
 *   （循环起点失效则作废弃置，否则重算窗口），再跑一遍结算补齐。
 * - 三个 public 入口共用一把互斥锁，配合实例表 (templateId, cycleStart) 唯一索引，
 *   防止并发结算重复插入。注意 Mutex 不可重入：锁只在 public 入口，私有 *Locked 方法不带锁。
 */
@Singleton
class RolloverManager @Inject constructor(
    private val templateDao: TaskTemplateDao,
    private val instanceDao: TaskInstanceDao
) {
    private val mutex = Mutex()

    suspend fun rolloverAll(
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ) = mutex.withLock {
        templateDao.getActive().forEach { rolloverTemplateLocked(it, nowMillis, zone) }
    }

    suspend fun rolloverTemplate(
        template: TaskTemplateEntity,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ) = mutex.withLock { rolloverTemplateLocked(template, nowMillis, zone) }

    /** 模板规则变更后修正当前实例并结算 */
    suspend fun regenerateActiveInstance(
        template: TaskTemplateEntity,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ) = mutex.withLock { regenerateLocked(template, nowMillis, zone) }

    /** 「重新开启」已作废任务：最新 EXPIRED 实例改回 ACTIVE 并按当前规则（新 anchor）重算窗口 */
    suspend fun reviveTemplate(
        template: TaskTemplateEntity,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ) = mutex.withLock {
        val latest = instanceDao.getLatestForTemplate(template.id) ?: return@withLock
        if (latest.status != InstanceStatus.EXPIRED.name) return@withLock
        instanceDao.markActive(latest.id)
        regenerateLocked(template, nowMillis, zone)
    }

    // ---------- 私有实现（调用方须已持锁） ----------

    private suspend fun rolloverTemplateLocked(
        template: TaskTemplateEntity,
        nowMillis: Long,
        zone: ZoneId
    ) {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        if (template.recurrenceType.toRecurrenceType() == RecurrenceType.NONE) {
            // 不循环：保证存在一条一次性实例；设了持续天数的到期后自动作废，不再生成新实例
            val latest = instanceDao.getLatestForTemplate(template.id)
            if (latest == null) {
                val created = insertInstance(
                    template,
                    RecurrenceEngine.currentCycleStartDate(today, template, zone),
                    zone
                )
                // 开始日期在过去：创建即过期，立即作废（与循环分支的过期处理对齐）
                expireIfPastDue(created, nowMillis)
            } else {
                expireIfPastDue(latest, nowMillis)
            }
            return
        }

        var latest: TaskInstanceEntity = instanceDao.getLatestForTemplate(template.id)
            ?: insertInstance(template, RecurrenceEngine.currentCycleStartDate(today, template, zone), zone)

        // 窗口已过：作废（若未完成）并逐循环补齐，直到当前窗口有效
        var guard = 0
        while (latest.windowEnd <= nowMillis && guard++ < MAX_CATCH_UP_CYCLES) {
            if (latest.status == InstanceStatus.ACTIVE.name) {
                instanceDao.markExpired(latest.id)
            }
            val lastCycle = Instant.ofEpochMilli(latest.cycleStart).atZone(zone).toLocalDate()
            latest = insertInstance(template, RecurrenceEngine.nextCycleStartDate(lastCycle, template), zone)
        }
    }

    private suspend fun regenerateLocked(
        template: TaskTemplateEntity,
        nowMillis: Long,
        zone: ZoneId
    ) {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val latest = instanceDao.getLatestForTemplate(template.id)
        if (latest != null && latest.status == InstanceStatus.ACTIVE.name) {
            val expected = RecurrenceEngine.currentCycleStartDate(today, template, zone)
            val latestCycle = Instant.ofEpochMilli(latest.cycleStart).atZone(zone).toLocalDate()
            if (template.recurrenceType.toRecurrenceType() != RecurrenceType.NONE && latestCycle != expected) {
                val expectedStart = expected.atStartOfDay(zone).toInstant().toEpochMilli()
                val existing = instanceDao.findByCycle(template.id, expectedStart)
                when {
                    existing != null -> {
                        // 目标循环已有历史实例：保留历史（COMPLETED/EXPIRED 不动），
                        // 删除旧规则下的幻影实例，避免 REPLACE 撞唯一索引销毁历史
                        instanceDao.deleteById(latest.id)
                    }
                    expected > latestCycle -> {
                        // 起点前移（旧循环起点在新规则下已过时）：作废弃置留痕，按新规则建新实例
                        instanceDao.markExpired(latest.id)
                        expireIfPastDue(insertInstance(template, expected, zone), nowMillis)
                    }
                    else -> {
                        // 起点后移且目标循环无历史：幻影实例就地改挂到新起点，
                        // 避免主页按最大 cycleStart 取到已作废实例而隐身
                        val window = RecurrenceEngine.computeWindow(expected, template, zone)
                        instanceDao.update(
                            latest.copy(
                                cycleStart = expectedStart,
                                windowStart = window.startMillis,
                                windowEnd = window.endMillis
                            )
                        )
                        instanceDao.getById(latest.id)?.let { expireIfPastDue(it, nowMillis) }
                    }
                }
            } else {
                // 同循环（或 NONE 换开始日期/天数）：就地重算窗口
                val window = RecurrenceEngine.computeWindow(expected, template, zone)
                instanceDao.update(
                    latest.copy(
                        cycleStart = expected.atStartOfDay(zone).toInstant().toEpochMilli(),
                        windowStart = window.startMillis,
                        windowEnd = window.endMillis
                    )
                )
                // 窗口改完可能整体过期（如开始日期改到过去）：立即作废
                instanceDao.getById(latest.id)?.let { expireIfPastDue(it, nowMillis) }
            }
        }
        rolloverTemplateLocked(template, nowMillis, zone)
    }

    /** 窗口已结束的 ACTIVE 实例立即作废 */
    private suspend fun expireIfPastDue(instance: TaskInstanceEntity, nowMillis: Long) {
        if (instance.status == InstanceStatus.ACTIVE.name &&
            instance.windowEnd != Long.MAX_VALUE && instance.windowEnd <= nowMillis
        ) {
            instanceDao.markExpired(instance.id)
        }
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

    companion object {
        /** 单次结算最多补齐的循环数（防长期未打开 App 时一次插入过多） */
        const val MAX_CATCH_UP_CYCLES = 500
    }
}
