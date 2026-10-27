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

    @Query("SELECT * FROM task_instances WHERE id = :id")
    suspend fun getById(id: Long): TaskInstanceEntity?

    @Query("SELECT * FROM task_instances WHERE templateId = :templateId AND cycleStart = :cycleStart LIMIT 1")
    suspend fun findByCycle(templateId: Long, cycleStart: Long): TaskInstanceEntity?

    @Query("DELETE FROM task_instances WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(instance: TaskInstanceEntity): Long

    @Update
    suspend fun update(instance: TaskInstanceEntity)

    @Query("UPDATE task_instances SET status = 'COMPLETED', completedAt = :completedAt WHERE id = :id")
    suspend fun markCompleted(id: Long, completedAt: Long)

    /** 完成后计时：完成的同时把窗口终点改写为冷却恢复日（零点毫秒） */
    @Query("UPDATE task_instances SET status = 'COMPLETED', completedAt = :completedAt, windowEnd = :windowEnd WHERE id = :id")
    suspend fun markCompletedWithWindowEnd(id: Long, completedAt: Long, windowEnd: Long)

    /** 完成后计时：撤销完成同时把窗口终点改回长期有效（取消冷却） */
    @Query("UPDATE task_instances SET status = 'ACTIVE', completedAt = NULL, windowEnd = :windowEnd WHERE id = :id")
    suspend fun markActiveWithWindowEnd(id: Long, windowEnd: Long)

    @Query("UPDATE task_instances SET status = 'EXPIRED' WHERE id = :id")
    suspend fun markExpired(id: Long)
}
