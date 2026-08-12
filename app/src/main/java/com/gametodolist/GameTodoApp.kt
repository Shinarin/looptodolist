package com.gametodolist

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.gametodolist.data.TodoRepository
import com.gametodolist.data.entity.CategoryEntity
import com.gametodolist.domain.CATEGORY_COLOR_PRESETS
import com.gametodolist.notify.RolloverWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class GameTodoApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var repository: TodoRepository

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        schedulePeriodicRollover()
        seedDefaultCategories()
    }

    /** 首次启动放入默认分类 */
    private fun seedDefaultCategories() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            if (repository.categoryCount() == 0) {
                listOf("工作", "生活", "学习").forEachIndexed { index, name ->
                    repository.upsertCategory(
                        CategoryEntity(
                            name = name,
                            color = CATEGORY_COLOR_PRESETS[index],
                            sortOrder = index
                        )
                    )
                }
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            REMINDER_CHANNEL_ID,
            getString(R.string.channel_reminder_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = getString(R.string.channel_reminder_desc) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun schedulePeriodicRollover() {
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "rollover_periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RolloverWorker>(12, TimeUnit.HOURS).build()
        )
    }

    companion object {
        const val REMINDER_CHANNEL_ID = "task_reminder"
    }
}
