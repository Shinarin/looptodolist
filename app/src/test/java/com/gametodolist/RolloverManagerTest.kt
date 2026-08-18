package com.gametodolist

import com.gametodolist.data.dao.TaskInstanceDao
import com.gametodolist.data.dao.TaskTemplateDao
import com.gametodolist.data.entity.TaskInstanceEntity
import com.gametodolist.data.entity.TaskTemplateEntity
import com.gametodolist.domain.InstanceStatus
import com.gametodolist.domain.RolloverManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

// ---------- 内存假 DAO ----------

private class FakeTemplateDao(private val templates: MutableList<TaskTemplateEntity>) : TaskTemplateDao {
    override fun observeActive(): Flow<List<TaskTemplateEntity>> = flowOf(templates.filter { !it.archived })
    override suspend fun getActive(): List<TaskTemplateEntity> = templates.filter { !it.archived }
    override suspend fun getById(id: Long): TaskTemplateEntity? = templates.find { it.id == id }
    override suspend fun insert(template: TaskTemplateEntity): Long {
        val id = (templates.maxOfOrNull { it.id } ?: 0) + 1
        templates.add(template.copy(id = id))
        return id
    }
    override suspend fun update(template: TaskTemplateEntity) {
        templates.removeAll { it.id == template.id }
        templates.add(template)
    }
    override suspend fun archive(id: Long) {
        templates.replaceAll { if (it.id == id) it.copy(archived = true) else it }
    }
    override suspend fun deleteById(id: Long) { templates.removeAll { it.id == id } }
    override suspend fun clearCategory(categoryId: Long) {
        templates.replaceAll { if (it.categoryId == categoryId) it.copy(categoryId = null) else it }
    }
}

private class FakeInstanceDao(val instances: MutableList<TaskInstanceEntity> = mutableListOf()) : TaskInstanceDao {
    override suspend fun getActive(): List<TaskInstanceEntity> = instances.filter { it.status == "ACTIVE" }
    override suspend fun getLatestForTemplate(templateId: Long): TaskInstanceEntity? =
        instances.filter { it.templateId == templateId }.maxByOrNull { it.cycleStart }
    override fun observeLatestPerTemplate(): Flow<List<TaskInstanceEntity>> =
        flowOf(instances.groupBy { it.templateId }.mapNotNull { (_, list) -> list.maxByOrNull { it.cycleStart } })
    override suspend fun markActive(id: Long) {
        instances.replaceAll { if (it.id == id) it.copy(status = "ACTIVE", completedAt = null) else it }
    }
    override suspend fun getById(id: Long): TaskInstanceEntity? = instances.find { it.id == id }
    override suspend fun findByCycle(templateId: Long, cycleStart: Long): TaskInstanceEntity? =
        instances.find { it.templateId == templateId && it.cycleStart == cycleStart }
    override suspend fun deleteById(id: Long) { instances.removeAll { it.id == id } }
    override suspend fun insert(instance: TaskInstanceEntity): Long {
        val id = if (instance.id == 0L) (instances.maxOfOrNull { it.id } ?: 0) + 1 else instance.id
        instances.removeAll { it.id == id }
        instances.add(instance.copy(id = id))
        return id
    }
    override suspend fun update(instance: TaskInstanceEntity) { insert(instance) }
    override suspend fun markCompleted(id: Long, completedAt: Long) {
        instances.replaceAll { if (it.id == id) it.copy(status = "COMPLETED", completedAt = completedAt) else it }
    }
    override suspend fun markExpired(id: Long) {
        instances.replaceAll { if (it.id == id) it.copy(status = "EXPIRED") else it }
    }
}

// ---------- 测试 ----------

class RolloverManagerTest {

    private val zone: ZoneId = ZoneOffset.UTC

    private fun millisOf(date: LocalDate, hour: Int = 0): Long =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun weeklyTemplate(id: Long = 1L, createdAt: Long = millisOf(LocalDate.of(2025, 1, 1))) =
        TaskTemplateEntity(
            id = id,
            categoryId = null,
            title = "周任务",
            recurrenceType = "WEEKLY",
            weekdaysMask = 0b0000101, // 周一 + 周三
            createdAt = createdAt
        )

    private fun manager(
        templates: MutableList<TaskTemplateEntity>,
        instances: FakeInstanceDao
    ) = RolloverManager(FakeTemplateDao(templates), instances)

    @Test
    fun `不循环任务只创建一次实例`() = runTest {
        val template = TaskTemplateEntity(id = 1, categoryId = null, title = "一次性", recurrenceType = "NONE")
        val instances = FakeInstanceDao()
        val m = manager(mutableListOf(template), instances)

        val now = millisOf(LocalDate.of(2025, 1, 10), 10)
        m.rolloverAll(now, zone)
        m.rolloverAll(now, zone)

        assertEquals(1, instances.instances.size)
        assertEquals(Long.MAX_VALUE, instances.instances[0].windowEnd)
        assertEquals(InstanceStatus.ACTIVE.name, instances.instances[0].status)
    }

    @Test
    fun `不循环带持续天数 到期自动作废且不再生成`() = runTest {
        val template = TaskTemplateEntity(
            id = 1,
            categoryId = null,
            title = "一次性",
            recurrenceType = "NONE",
            windowMode = "CUSTOM",
            customWindowDays = 3,
            anchorDate = millisOf(LocalDate.of(2025, 1, 10))
        )
        val instances = FakeInstanceDao()
        val m = manager(mutableListOf(template), instances)

        // 创建：窗口 [1-10, 1-13)
        m.rolloverAll(millisOf(LocalDate.of(2025, 1, 10), 10), zone)
        assertEquals(1, instances.instances.size)
        val inst = instances.instances[0]
        assertEquals(millisOf(LocalDate.of(2025, 1, 13)), inst.windowEnd)
        assertEquals(InstanceStatus.ACTIVE.name, inst.status)

        // 窗口内不作废
        m.rolloverAll(millisOf(LocalDate.of(2025, 1, 12), 10), zone)
        assertEquals(InstanceStatus.ACTIVE.name, instances.instances[0].status)

        // 过期后作废，且不再生成新实例
        m.rolloverAll(millisOf(LocalDate.of(2025, 1, 14), 10), zone)
        assertEquals(1, instances.instances.size)
        assertEquals(InstanceStatus.EXPIRED.name, instances.instances[0].status)
    }

    @Test
    fun `新模板创建当前循环的实例`() = runTest {
        val instances = FakeInstanceDao()
        val m = manager(mutableListOf(weeklyTemplate()), instances)

        // 2025-01-09 周四：当前循环锚点应为 周三 2025-01-08
        m.rolloverAll(millisOf(LocalDate.of(2025, 1, 9), 10), zone)

        assertEquals(1, instances.instances.size)
        val inst = instances.instances[0]
        assertEquals(InstanceStatus.ACTIVE.name, inst.status)
        assertEquals(millisOf(LocalDate.of(2025, 1, 8)), inst.cycleStart)
        assertEquals(millisOf(LocalDate.of(2025, 1, 13)), inst.windowEnd) // 下一锚点是下周一
    }

    @Test
    fun `过期实例自动作废并逐循环补齐`() = runTest {
        val instances = FakeInstanceDao(
            mutableListOf(
                TaskInstanceEntity(
                    id = 1,
                    templateId = 1,
                    cycleStart = millisOf(LocalDate.of(2025, 1, 6)),   // 周一
                    windowStart = millisOf(LocalDate.of(2025, 1, 6)),
                    windowEnd = millisOf(LocalDate.of(2025, 1, 8)),     // 周三
                    status = "ACTIVE"
                )
            )
        )
        val m = manager(mutableListOf(weeklyTemplate()), instances)

        // 推进到 2025-01-14 周二：跨过 周三1-08、周一1-13 两个循环
        m.rolloverAll(millisOf(LocalDate.of(2025, 1, 14), 10), zone)

        assertEquals(3, instances.instances.size)
        val expired = instances.instances.filter { it.status == InstanceStatus.EXPIRED.name }
        val active = instances.instances.filter { it.status == InstanceStatus.ACTIVE.name }
        assertEquals(2, expired.size)
        assertEquals(1, active.size)
        assertEquals(millisOf(LocalDate.of(2025, 1, 13)), active[0].cycleStart)
        assertEquals(millisOf(LocalDate.of(2025, 1, 15)), active[0].windowEnd)
    }

    @Test
    fun `已完成实例不作废只生成新循环`() = runTest {
        val instances = FakeInstanceDao(
            mutableListOf(
                TaskInstanceEntity(
                    id = 1,
                    templateId = 1,
                    cycleStart = millisOf(LocalDate.of(2025, 1, 6)),
                    windowStart = millisOf(LocalDate.of(2025, 1, 6)),
                    windowEnd = millisOf(LocalDate.of(2025, 1, 8)),
                    status = "COMPLETED",
                    completedAt = millisOf(LocalDate.of(2025, 1, 7))
                )
            )
        )
        val m = manager(mutableListOf(weeklyTemplate()), instances)

        m.rolloverAll(millisOf(LocalDate.of(2025, 1, 9), 10), zone)

        assertEquals(2, instances.instances.size)
        assertEquals(
            1,
            instances.instances.count { it.status == InstanceStatus.COMPLETED.name }
        )
        assertTrue(instances.instances.none { it.status == InstanceStatus.EXPIRED.name })
        val active = instances.instances.single { it.status == InstanceStatus.ACTIVE.name }
        assertEquals(millisOf(LocalDate.of(2025, 1, 8)), active.cycleStart)
    }

    @Test
    fun `不循环带持续天数 开始日期在过去 创建即作废`() = runTest {
        val template = TaskTemplateEntity(
            id = 1, categoryId = null, title = "一次性", recurrenceType = "NONE",
            windowMode = "CUSTOM", customWindowDays = 3,
            anchorDate = millisOf(LocalDate.of(2025, 1, 1))
        )
        val instances = FakeInstanceDao()
        val m = manager(mutableListOf(template), instances)

        // 1-10 才首次结算：窗口 [1-1, 1-4) 早已过期，应立即作废而不是挂着 ACTIVE
        m.rolloverAll(millisOf(LocalDate.of(2025, 1, 10), 10), zone)

        assertEquals(1, instances.instances.size)
        assertEquals(InstanceStatus.EXPIRED.name, instances.instances[0].status)
    }

    @Test
    fun `编辑把不循环任务开始日期改到过去 立即作废`() = runTest {
        val template = TaskTemplateEntity(
            id = 1, categoryId = null, title = "一次性", recurrenceType = "NONE",
            windowMode = "CUSTOM", customWindowDays = 3,
            anchorDate = millisOf(LocalDate.of(2025, 1, 9))
        )
        val instances = FakeInstanceDao()
        val m = manager(mutableListOf(template), instances)
        m.rolloverAll(millisOf(LocalDate.of(2025, 1, 10), 10), zone)
        assertEquals(InstanceStatus.ACTIVE.name, instances.instances[0].status)

        // 用户把开始日期改到 1-01（窗口 [1-1,1-4) 已过期），保存触发 regenerate
        m.regenerateActiveInstance(
            template.copy(anchorDate = millisOf(LocalDate.of(2025, 1, 1))),
            millisOf(LocalDate.of(2025, 1, 10), 10), zone
        )

        assertEquals(InstanceStatus.EXPIRED.name, instances.instances[0].status)
    }

    @Test
    fun `编辑改每周规则导致循环起点变化 旧实例作废并生成新循环`() = runTest {
        val instances = FakeInstanceDao()
        val m = manager(mutableListOf(weeklyTemplate()), instances)
        // 2025-01-09 周四：当前循环起点周三 1-08
        m.rolloverAll(millisOf(LocalDate.of(2025, 1, 9), 10), zone)
        assertEquals(millisOf(LocalDate.of(2025, 1, 8)), instances.instances[0].cycleStart)

        // 规则从「周一+周三」改成「周二+周四」：周四 1-09 回推 0 天即命中周四本身（起点前移），
        // 旧实例（起点周三 1-08）作废，新实例起点周四 1-09，窗口到下一命中日周二 1-14
        m.regenerateActiveInstance(
            weeklyTemplate().copy(weekdaysMask = 0b0001010), // 周二 + 周四
            millisOf(LocalDate.of(2025, 1, 9), 10), zone
        )

        val expired = instances.instances.filter { it.status == InstanceStatus.EXPIRED.name }
        val active = instances.instances.filter { it.status == InstanceStatus.ACTIVE.name }
        assertEquals(1, expired.size)
        assertEquals(1, active.size)
        assertEquals(millisOf(LocalDate.of(2025, 1, 9)), active[0].cycleStart) // 周四命中
        assertEquals(millisOf(LocalDate.of(2025, 1, 14)), active[0].windowEnd) // 下一锚点周二
    }

    @Test
    fun `编辑已完成模板且当前循环已过期 结算补出新循环`() = runTest {
        val instances = FakeInstanceDao(
            mutableListOf(
                TaskInstanceEntity(
                    id = 1, templateId = 1,
                    cycleStart = millisOf(LocalDate.of(2025, 1, 6)),
                    windowStart = millisOf(LocalDate.of(2025, 1, 6)),
                    windowEnd = millisOf(LocalDate.of(2025, 1, 8)),
                    status = "COMPLETED", completedAt = millisOf(LocalDate.of(2025, 1, 7))
                )
            )
        )
        val m = manager(mutableListOf(weeklyTemplate()), instances)

        m.regenerateActiveInstance(weeklyTemplate(), millisOf(LocalDate.of(2025, 1, 10), 10), zone)

        assertEquals(1, instances.instances.count { it.status == InstanceStatus.COMPLETED.name })
        val active = instances.instances.single { it.status == InstanceStatus.ACTIVE.name }
        assertEquals(millisOf(LocalDate.of(2025, 1, 8)), active.cycleStart)
    }

    @Test
    fun `regenerate 无实例时按规则建新实例`() = runTest {
        val instances = FakeInstanceDao()
        val m = manager(mutableListOf(weeklyTemplate()), instances)

        m.regenerateActiveInstance(weeklyTemplate(), millisOf(LocalDate.of(2025, 1, 9), 10), zone)

        assertEquals(1, instances.instances.size)
        assertEquals(InstanceStatus.ACTIVE.name, instances.instances[0].status)
    }

    @Test
    fun `编辑规则起点后移且目标循环有历史 保留历史并删除幻影实例`() = runTest {
        val instances = FakeInstanceDao(
            mutableListOf(
                TaskInstanceEntity(
                    id = 1, templateId = 1,
                    cycleStart = millisOf(LocalDate.of(2025, 1, 6)),   // 周一（历史已完成）
                    windowStart = millisOf(LocalDate.of(2025, 1, 6)),
                    windowEnd = millisOf(LocalDate.of(2025, 1, 8)),
                    status = "COMPLETED", completedAt = millisOf(LocalDate.of(2025, 1, 7))
                ),
                TaskInstanceEntity(
                    id = 2, templateId = 1,
                    cycleStart = millisOf(LocalDate.of(2025, 1, 8)),   // 周三（当前 ACTIVE）
                    windowStart = millisOf(LocalDate.of(2025, 1, 8)),
                    windowEnd = millisOf(LocalDate.of(2025, 1, 13)),
                    status = "ACTIVE"
                )
            )
        )
        val m = manager(mutableListOf(weeklyTemplate()), instances)

        // 规则从「周一+周三」改成「仅周一」，今天周三 1-08：新当前循环起点 = 周一 1-06（后移且有历史）
        m.regenerateActiveInstance(
            weeklyTemplate().copy(weekdaysMask = 0b0000001),
            millisOf(LocalDate.of(2025, 1, 8), 10), zone
        )

        // 历史 COMPLETED 原样保留（completedAt 不丢），幻影 ACTIVE 被删除；
        // 结算随后按新规则补出下一循环（周一 1-13）
        assertEquals(2, instances.instances.size)
        val kept = instances.instances.single { it.status == InstanceStatus.COMPLETED.name }
        assertEquals(millisOf(LocalDate.of(2025, 1, 7)), kept.completedAt)
        assertEquals(millisOf(LocalDate.of(2025, 1, 6)), kept.cycleStart)
        val next = instances.instances.single { it.status == InstanceStatus.ACTIVE.name }
        assertEquals(millisOf(LocalDate.of(2025, 1, 13)), next.cycleStart)
    }

    @Test
    fun `编辑规则起点后移且目标循环无历史 幻影实例就地改挂新起点`() = runTest {
        val instances = FakeInstanceDao(
            mutableListOf(
                TaskInstanceEntity(
                    id = 2, templateId = 1,
                    cycleStart = millisOf(LocalDate.of(2025, 1, 8)),   // 周三（当前 ACTIVE）
                    windowStart = millisOf(LocalDate.of(2025, 1, 8)),
                    windowEnd = millisOf(LocalDate.of(2025, 1, 13)),
                    status = "ACTIVE"
                )
            )
        )
        val m = manager(mutableListOf(weeklyTemplate()), instances)

        // 规则改成「仅周五」，今天周三 1-08：回推命中上周五 1-03（后移且无历史）
        m.regenerateActiveInstance(
            weeklyTemplate().copy(weekdaysMask = 0b0010000),
            millisOf(LocalDate.of(2025, 1, 8), 10), zone
        )

        // 同一条实例被改挂：cycleStart=1-03，窗口按新规则到下一周五 1-10
        assertEquals(1, instances.instances.size)
        val inst = instances.instances[0]
        assertEquals(InstanceStatus.ACTIVE.name, inst.status)
        assertEquals(millisOf(LocalDate.of(2025, 1, 3)), inst.cycleStart)
        assertEquals(millisOf(LocalDate.of(2025, 1, 10)), inst.windowEnd)
    }

    @Test
    fun `复活已作废的不循环任务 窗口从今天重算`() = runTest {
        val template = TaskTemplateEntity(
            id = 1, categoryId = null, title = "一次性", recurrenceType = "NONE",
            windowMode = "CUSTOM", customWindowDays = 3,
            anchorDate = millisOf(LocalDate.of(2025, 1, 1))
        )
        val instances = FakeInstanceDao()
        val m = manager(mutableListOf(template), instances)
        m.rolloverAll(millisOf(LocalDate.of(2025, 1, 10), 10), zone)
        assertEquals(InstanceStatus.EXPIRED.name, instances.instances[0].status)

        // 用户点「重新开启」：ViewModel 已把 anchor 更新为今天（1-10）
        m.reviveTemplate(
            template.copy(anchorDate = millisOf(LocalDate.of(2025, 1, 10))),
            millisOf(LocalDate.of(2025, 1, 10), 12), zone
        )

        val revived = instances.instances[0]
        assertEquals(InstanceStatus.ACTIVE.name, revived.status)
        assertEquals(millisOf(LocalDate.of(2025, 1, 10)), revived.windowStart)
        assertEquals(millisOf(LocalDate.of(2025, 1, 13)), revived.windowEnd)
    }

    @Test
    fun `复活已作废的循环任务 窗口未过直接复活原实例`() = runTest {
        // 场景：编辑留痕导致循环任务最新实例 EXPIRED 但窗口仍覆盖现在（不会自动续期）
        val instances = FakeInstanceDao(
            mutableListOf(
                TaskInstanceEntity(
                    id = 1,
                    templateId = 1,
                    cycleStart = millisOf(LocalDate.of(2025, 1, 13)),   // 周一
                    windowStart = millisOf(LocalDate.of(2025, 1, 13)),
                    windowEnd = millisOf(LocalDate.of(2025, 1, 15)),     // 周三
                    status = "EXPIRED"
                )
            )
        )
        val m = manager(mutableListOf(weeklyTemplate()), instances)

        // 2025-01-14 周二，窗口内：复活后应直接恢复 ACTIVE，不产生新实例
        m.reviveTemplate(weeklyTemplate(), millisOf(LocalDate.of(2025, 1, 14), 10), zone)

        assertEquals(1, instances.instances.size)
        val revived = instances.instances[0]
        assertEquals(InstanceStatus.ACTIVE.name, revived.status)
        assertEquals(millisOf(LocalDate.of(2025, 1, 13)), revived.cycleStart)
    }

    @Test
    fun `复活已作废的循环任务 旧循环留痕并生成当前循环`() = runTest {
        // 场景：最新实例是更早循环的 EXPIRED，复活应留痕旧实例并补当前循环
        val instances = FakeInstanceDao(
            mutableListOf(
                TaskInstanceEntity(
                    id = 1,
                    templateId = 1,
                    cycleStart = millisOf(LocalDate.of(2025, 1, 6)),    // 上周一
                    windowStart = millisOf(LocalDate.of(2025, 1, 6)),
                    windowEnd = millisOf(LocalDate.of(2025, 1, 8)),
                    status = "EXPIRED"
                )
            )
        )
        val m = manager(mutableListOf(weeklyTemplate()), instances)

        m.reviveTemplate(weeklyTemplate(), millisOf(LocalDate.of(2025, 1, 14), 10), zone)

        assertEquals(2, instances.instances.size)
        assertEquals(InstanceStatus.EXPIRED.name, instances.instances.first { it.id == 1L }.status)
        val active = instances.instances.single { it.status == InstanceStatus.ACTIVE.name }
        assertEquals(millisOf(LocalDate.of(2025, 1, 13)), active.cycleStart)
        assertEquals(millisOf(LocalDate.of(2025, 1, 15)), active.windowEnd)
    }

    @Test
    fun `编辑已作废模板保存后复活`() = runTest {
        // 一次性任务到期作废后，编辑把开始日期改到今天再保存：应复活而不是停在已作废
        val template = TaskTemplateEntity(
            id = 1, categoryId = null, title = "一次性", recurrenceType = "NONE",
            windowMode = "CUSTOM", customWindowDays = 3,
            anchorDate = millisOf(LocalDate.of(2025, 1, 10))
        )
        val instances = FakeInstanceDao()
        val m = manager(mutableListOf(template), instances)
        m.rolloverAll(millisOf(LocalDate.of(2025, 1, 14), 10), zone)   // 创建即过期作废
        assertEquals(InstanceStatus.EXPIRED.name, instances.instances[0].status)

        // 编辑保存（开始日期改到 1-14）：走 regenerateActiveInstance
        m.regenerateActiveInstance(
            template.copy(anchorDate = millisOf(LocalDate.of(2025, 1, 14))),
            millisOf(LocalDate.of(2025, 1, 14), 12), zone
        )

        assertEquals(1, instances.instances.size)
        val revived = instances.instances[0]
        assertEquals(InstanceStatus.ACTIVE.name, revived.status)
        assertEquals(millisOf(LocalDate.of(2025, 1, 14)), revived.windowStart)
        assertEquals(millisOf(LocalDate.of(2025, 1, 17)), revived.windowEnd)
    }
}
