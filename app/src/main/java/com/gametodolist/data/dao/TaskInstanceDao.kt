package com.gametodolist.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.gametodolist.data.entity.TaskInstanceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskInstanceDao {
    @Query("SELECT * FROM task_instances WHERE status = 'ACTIVE'")
    fun observeActive(): Flow<List<TaskInstanceEntity>>

    @Query("SELECT * FROM task_instances WHERE status = 'ACTIVE'")
    suspend fun getActive(): List<TaskInstanceEntity>

    @Query("SELECT * FROM task_instances WHERE templateId = :templateId ORDER BY cycleStart DESC LIMIT 1")
    suspend fun getLatestForTemplate(templateId: Long): TaskInstanceEntity?

    /** 每个模板最新一条实例（按循环起点），主页/小组件展示用 */
    @Query(
        "SELECT ti.* FROM task_instances ti INNER JOIN " +
            "(SELECT templateId, MAX(cycleStart) AS maxCycle FROM task_instances GROUP BY templateId) latest " +
            "ON ti.templateId = latest.templateId AND ti.cycleStart = latest.maxCycle"
    )
    fun observeLatestPerTemplate(): Flow<List<TaskInstanceEntity>>

    @Query("UPDATE task_instances SET status = 'ACTIVE', completedAt = NULL WHERE id = :id")
    suspend fun markActive(id: Long)

    // 窗口与本范围重叠，或循环起点落在本范围内；无截止的一次性任务不参与窗口匹配
    @Query(
        "SELECT * FROM task_instances WHERE " +
            "(windowEnd != 9223372036854775807 AND windowStart < :rangeEnd AND windowEnd > :rangeStart) " +
            "OR (cycleStart >= :rangeStart AND cycleStart < :rangeEnd)"
    )
    fun observeInRange(rangeStart: Long, rangeEnd: Long): Flow<List<TaskInstanceEntity>>

    @Query("SELECT * FROM task_instances WHERE id = :id")
    suspend fun getById(id: Long): TaskInstanceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(instance: TaskInstanceEntity): Long

    @Update
    suspend fun update(instance: TaskInstanceEntity)

    @Query("UPDATE task_instances SET status = 'COMPLETED', completedAt = :completedAt WHERE id = :id")
    suspend fun markCompleted(id: Long, completedAt: Long)

    @Query("UPDATE task_instances SET status = 'EXPIRED' WHERE id = :id")
    suspend fun markExpired(id: Long)
}
