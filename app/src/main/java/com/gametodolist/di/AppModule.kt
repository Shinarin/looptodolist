package com.gametodolist.di

import android.content.Context
import androidx.room.Room
import com.gametodolist.data.AppDatabase
import com.gametodolist.data.MIGRATION_1_2
import com.gametodolist.data.dao.CategoryDao
import com.gametodolist.data.dao.TaskInstanceDao
import com.gametodolist.data.dao.TaskTemplateDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "gametodo.db")
            .addMigrations(MIGRATION_1_2)
            .build()

    @Provides
    fun provideCategoryDao(db: AppDatabase): CategoryDao = db.categoryDao()

    @Provides
    fun provideTaskTemplateDao(db: AppDatabase): TaskTemplateDao = db.taskTemplateDao()

    @Provides
    fun provideTaskInstanceDao(db: AppDatabase): TaskInstanceDao = db.taskInstanceDao()
}
