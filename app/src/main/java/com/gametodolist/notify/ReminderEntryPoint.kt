package com.gametodolist.notify

import android.content.Context
import com.gametodolist.data.dao.TaskInstanceDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderEntryPoint {
    fun instanceDao(): TaskInstanceDao
    fun reminderScheduler(): ReminderScheduler

    companion object {
        fun get(context: Context): ReminderEntryPoint =
            EntryPointAccessors.fromApplication(context.applicationContext, ReminderEntryPoint::class.java)
    }
}
