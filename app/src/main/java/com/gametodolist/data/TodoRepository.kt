package com.gametodolist.data

import com.gametodolist.data.dao.CategoryDao
import com.gametodolist.data.dao.TaskInstanceDao
import com.gametodolist.data.dao.TaskTemplateDao
import com.gametodolist.data.entity.CategoryEntity
import com.gametodolist.data.entity.TaskInstanceEntity
import com.gametodolist.data.entity.TaskTemplateEntity
import com.gametodolist.domain.InstanceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/** 模板 + 当前有效实例（UI 展示用） */
data class TemplateWithInstance(
    val template: TaskTemplateEntity,
    val activeInstance: TaskInstanceEntity?
)

/** 分类分组：category 为 null 表示「未分类」组 */
data class CategoryGroup(
    val category: CategoryEntity?,
    val items: List<TemplateWithInstance>
)

/** 主页数据：未完成 / 已完成 两个分区，各自再按分类分组 */
data class HomeData(
    val activeGroups: List<CategoryGroup>,
    val completedGroups: List<CategoryGroup>
)

@Singleton
class TodoRepository @Inject constructor(
    private val categoryDao: CategoryDao,
    private val templateDao: TaskTemplateDao,
    private val instanceDao: TaskInstanceDao
) {

    // ---------- 分类 ----------
    fun observeCategories(): Flow<List<CategoryEntity>> = categoryDao.observeAll()

    suspend fun upsertCategory(category: CategoryEntity): Long = categoryDao.insert(category)

    suspend fun deleteCategory(category: CategoryEntity) {
        templateDao.clearCategory(category.id)
        categoryDao.delete(category)
    }

    suspend fun categoryCount(): Int = categoryDao.count()

    // ---------- 清单模板 ----------
    suspend fun getTemplate(id: Long): TaskTemplateEntity? = templateDao.getById(id)

    suspend fun saveTemplate(template: TaskTemplateEntity): Long = templateDao.insert(template)

    suspend fun deleteTemplate(id: Long) = templateDao.deleteById(id)

    // ---------- 任务实例 ----------
    suspend fun completeInstance(instanceId: Long) =
        instanceDao.markCompleted(instanceId, System.currentTimeMillis())

    suspend fun uncompleteInstance(instanceId: Long) = instanceDao.markActive(instanceId)

    suspend fun getActiveInstances(): List<TaskInstanceEntity> = instanceDao.getActive()

    /** 所有未归档模板 */
    fun observeTemplates(): Flow<List<TaskTemplateEntity>> = templateDao.observeActive()

    /** 每个模板的最新实例 */
    fun observeLatestInstances(): Flow<List<TaskInstanceEntity>> = instanceDao.observeLatestPerTemplate()

    /** 主页数据：按每个模板的最新实例分为「未完成」与「已完成」两区，
     * 各区内再按分类分组；最新实例已作废（EXPIRED）的不显示。
     */
    fun observeHomeData(): Flow<HomeData> =
        combine(
            categoryDao.observeAll(),
            templateDao.observeActive(),
            instanceDao.observeLatestPerTemplate()
        ) { categories, templates, latestInstances ->
            val instanceByTemplate = latestInstances.associateBy { it.templateId }

            fun groupsFor(status: String): List<CategoryGroup> {
                val groups = templates
                    .filter { instanceByTemplate[it.id]?.status == status }
                    .groupBy { it.categoryId }
                val result = mutableListOf<CategoryGroup>()
                categories.forEach { cat ->
                    val items = groups[cat.id].orEmpty()
                        .map { TemplateWithInstance(it, instanceByTemplate[it.id]) }
                    if (items.isNotEmpty()) result.add(CategoryGroup(cat, items))
                }
                groups[null]?.takeIf { it.isNotEmpty() }?.let { uncategorized ->
                    result.add(CategoryGroup(null, uncategorized.map {
                        TemplateWithInstance(it, instanceByTemplate[it.id])
                    }))
                }
                return result
            }

            HomeData(
                activeGroups = groupsFor(InstanceStatus.ACTIVE.name),
                completedGroups = groupsFor(InstanceStatus.COMPLETED.name)
            )
        }
}
