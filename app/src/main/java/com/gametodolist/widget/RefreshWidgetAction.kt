package com.gametodolist.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback

/** 小组件上的「手动刷新」按钮：重新结算并同步 App 最新数据与外观设置 */
class RefreshWidgetAction : ActionCallback {

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        WidgetEntryPoint.get(context).dataSync().settleAndRefresh()
    }
}
