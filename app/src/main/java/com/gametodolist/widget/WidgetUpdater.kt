package com.gametodolist.widget

import android.content.Context
import androidx.glance.appwidget.updateAll

/** 数据变更后刷新所有桌面小组件 */
object WidgetUpdater {
    suspend fun refresh(context: Context) {
        runCatching { TodoWidget().updateAll(context) }
    }
}
