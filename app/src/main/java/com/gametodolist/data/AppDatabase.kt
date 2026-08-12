package com.gametodolist.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.gametodolist.data.dao.CategoryDao
import com.gametodolist.data.dao.TaskInstanceDao
import com.gametodolist.data.dao.TaskTemplateDao
import com.gametodolist.data.entity.CategoryEntity
import com.gametodolist.data.entity.TaskInstanceEntity
import com.gametodolist.data.entity.TaskTemplateEntity

@Database(
    entities = [CategoryEntity::class, TaskTemplateEntity::class, TaskInstanceEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun taskTemplateDao(): TaskTemplateDao
    abstract fun taskInstanceDao(): TaskInstanceDao
}
