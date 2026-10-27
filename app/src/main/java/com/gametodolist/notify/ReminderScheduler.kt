package com.gametodolist.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.gametodolist.data.dao.TaskInstanceDao
import com.gametodolist.data.dao.TaskTemplateDao
import com.gametodolist.domain.InstanceStatus
import com.gametodolist.domain.RecurrenceType
import com.gametodolist.domain.toRecurrenceType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 到期提醒调度：基于 AlarmManager 精确闹钟 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val templateDao: TaskTemplateDao,
    private val instanceDao: TaskInstanceDao
) {
    private val alarmManager: AlarmManager
        get() = context.getSystemService(AlarmManager::class.java)

    private fun pendingIntent(instanceId: Long, title: String, body: String? = null): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_INSTANCE_ID, instanceId)
            putExtra(ReminderReceiver.EXTRA_TITLE, title)
            if (body != null) putExtra(ReminderReceiver.EXTRA_BODY, body)
        }
        return PendingIntent.getBroadcast(
            context, (instanceId xor Int.MIN_VALUE.toLong()).toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(instanceId: Long, title: String, triggerAtMillis: Long, body: String? = null) {
        if (triggerAtMillis <= System.currentTimeMillis()) return
        runCatching {
            if (canScheduleExactReminders()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent(instanceId, title, body)
                )
            } else {
                // Android 14+ 未授权精确闹钟：降级非精确（可能延迟，但不崩溃）
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent(instanceId, title, body)
                )
            }
        }.onFailure { Log.w(TAG, "schedule failed for instance $instanceId", it) }
    }

    /** 是否可精确提醒（API 31 以下或已授权精确闹钟） */
    fun canScheduleExactReminders(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    fun cancel(instanceId: Long) {
        alarmManager.cancel(pendingIntent(instanceId, ""))
    }

    /** 为某个实例按其模板配置调度提醒 */
    suspend fun scheduleForInstance(instanceId: Long) {
        val instance = instanceDao.getById(instanceId) ?: return
        if (instance.status != InstanceStatus.ACTIVE.name) return
        val template = templateDao.getById(instance.templateId) ?: return
        if (!template.reminderEnabled) return
        if (template.recurrenceType.toRecurrenceType() == RecurrenceType.AFTER_COMPLETION) {
            // 完成后计时：无截止时间，在恢复日（= 新实例循环起点）当天 09:00 提醒；
            // 触发时刻已过由 schedule 内部丢弃，当天过点不再补发
            schedule(
                instance.id, template.title,
                instance.cycleStart + COOLDOWN_REMINDER_HOUR * 3_600_000L,
                body = "已恢复待办，记得处理哦"
            )
            return
        }
        if (instance.windowEnd == Long.MAX_VALUE) return
        schedule(
            instance.id, template.title,
            instance.windowEnd - template.reminderMinutesBefore * 60_000L
        )
    }

    /** 全量重排（开机后 / 周期结算后调用） */
    suspend fun rescheduleAll() {
        instanceDao.getActive().forEach { scheduleForInstance(it.id) }
    }

    private companion object {
        const val TAG = "ReminderScheduler"
        /** 完成后计时：恢复日当天的提醒时刻（当地 09:00） */
        const val COOLDOWN_REMINDER_HOUR = 9
    }
}
