package com.gametodolist.notify

import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import android.content.Context
import com.gametodolist.domain.RolloverManager
import com.gametodolist.widget.WidgetUpdater
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** 周期结算：作废过期任务、生成新循环、重排提醒、同步桌面小组件 */
@HiltWorker
class RolloverWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val rolloverManager: RolloverManager,
    private val reminderScheduler: ReminderScheduler
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        rolloverManager.rolloverAll()
        reminderScheduler.rescheduleAll()
        WidgetUpdater.refresh(applicationContext)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}
