package com.gametodolist.widget

import com.gametodolist.data.TodoRepository
import com.gametodolist.domain.RolloverManager
import com.gametodolist.notify.ReminderScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import android.content.Context

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun repository(): TodoRepository
    fun rolloverManager(): RolloverManager
    fun reminderScheduler(): ReminderScheduler

    companion object {
        fun get(context: Context): WidgetEntryPoint =
            EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
    }
}
