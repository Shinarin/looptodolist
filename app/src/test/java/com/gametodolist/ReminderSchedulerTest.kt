package com.gametodolist

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gametodolist.data.AppDatabase
import com.gametodolist.data.entity.TaskInstanceEntity
import com.gametodolist.data.entity.TaskTemplateEntity
import com.gametodolist.notify.ReminderScheduler
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ReminderSchedulerTest {
    private lateinit var db: AppDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `停用取消提醒且恢复后可重新调度`() = runTest {
        val templateDao = db.taskTemplateDao()
        val instanceDao = db.taskInstanceDao()
        val scheduler = ReminderScheduler(context, templateDao, instanceDao)
        val shadowAlarmManager = shadowOf(context.getSystemService(AlarmManager::class.java))
        val templateId = templateDao.insert(
            TaskTemplateEntity(
                categoryId = null,
                title = "提醒任务",
                reminderEnabled = true,
                reminderMinutesBefore = 60
            )
        )
        val instanceId = instanceDao.insert(
            TaskInstanceEntity(
                templateId = templateId,
                cycleStart = 1000,
                windowStart = 1000,
                windowEnd = System.currentTimeMillis() + 3 * 60 * 60 * 1000L
            )
        )

        scheduler.scheduleForInstance(instanceId)
        assertNotNull(shadowAlarmManager.peekNextScheduledAlarm())

        templateDao.update(templateDao.getById(templateId)!!.copy(paused = true))
        scheduler.scheduleForInstance(instanceId)
        assertNull(shadowAlarmManager.peekNextScheduledAlarm())

        templateDao.update(templateDao.getById(templateId)!!.copy(paused = false))
        scheduler.scheduleForInstance(instanceId)
        assertNotNull(shadowAlarmManager.peekNextScheduledAlarm())
    }
}
