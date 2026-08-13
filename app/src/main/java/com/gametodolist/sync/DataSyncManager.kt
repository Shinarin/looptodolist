package com.gametodolist.sync

import android.content.Context
import android.util.Log
import com.gametodolist.domain.RolloverManager
import com.gametodolist.notify.ReminderScheduler
import com.gametodolist.widget.WidgetUpdater
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 数据变更统一管线：任何任务/模板/分类写操作后调用 [settleAndRefresh]。
 * 结算（作废过期+补新循环）→ 全量重排提醒（只排 ACTIVE，刚完成/删除的自动跳过）
 * → 刷新所有桌面组件。三步全量幂等；单步失败记日志，互不影响。
 *
 * 例外：设置页外观改动不涉及任务数据，仍只调 WidgetUpdater.refresh。
 */
@Singleton
class DataSyncManager @Inject constructor(
    private val rolloverManager: RolloverManager,
    private val reminderScheduler: ReminderScheduler,
    @ApplicationContext private val context: Context
) {
    suspend fun settleAndRefresh() {
        runCatching { rolloverManager.rolloverAll() }
            .onFailure { Log.w(TAG, "rollover failed", it) }
        runCatching { reminderScheduler.rescheduleAll() }
            .onFailure { Log.w(TAG, "reschedule failed", it) }
        WidgetUpdater.refresh(context)
    }

    private companion object {
        const val TAG = "DataSyncManager"
    }
}
