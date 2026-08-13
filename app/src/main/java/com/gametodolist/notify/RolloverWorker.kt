package com.gametodolist.notify

import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import android.content.Context
import com.gametodolist.sync.DataSyncManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** 周期结算：作废过期任务、生成新循环、重排提醒、同步桌面小组件 */
@HiltWorker
class RolloverWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dataSync: DataSyncManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        dataSync.settleAndRefresh()
        Result.success()
    } catch (e: Exception) {
        // 只对有界次数重试，避免权限/数据类永久错误无限重试
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
