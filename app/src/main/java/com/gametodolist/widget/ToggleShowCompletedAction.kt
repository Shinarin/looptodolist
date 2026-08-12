package com.gametodolist.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState

/** 小组件上「已完成」区的展开/收起切换（状态存在 Glance 原子状态里，写入即触发重组） */
class ToggleShowCompletedAction : ActionCallback {

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[KEY_SHOW_COMPLETED] = !(prefs[KEY_SHOW_COMPLETED] ?: false)
        }
        TodoWidget().update(context, glanceId)
    }
}
