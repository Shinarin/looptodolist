package com.gametodolist.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.gametodolist.data.entity.TaskTemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskTemplateDao {
    @Query("SELECT * FROM task_templates WHERE archived = 0 ORDER BY sortOrder, id")
    fun observeActive(): Flow<List<TaskTemplateEntity>>

    @Query("SELECT * FROM task_templates WHERE archived = 0")
    suspend fun getActive(): List<TaskTemplateEntity>

    @Query("SELECT * FROM task_templates WHERE id = :id")
    suspend fun getById(id: Long): TaskTemplateEntity?

    @Insert
    suspend fun insert(template: TaskTemplateEntity): Long

    @Update
    suspend fun update(template: TaskTemplateEntity)

    @Query("UPDATE task_templates SET archived = 1 WHERE id = :id")
    suspend fun archive(id: Long)

    @Query("DELETE FROM task_templates WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE task_templates SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: Long)
}
