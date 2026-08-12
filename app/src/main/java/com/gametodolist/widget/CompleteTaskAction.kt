package com.gametodolist.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll

/** 小组件上的「完成」动作 */
class CompleteTaskAction : ActionCallback {

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val instanceId = parameters[INSTANCE_ID_KEY] ?: return
        val entryPoint = WidgetEntryPoint.get(context)
        entryPoint.reminderScheduler().cancel(instanceId)
        entryPoint.repository().completeInstance(instanceId)
        entryPoint.rolloverManager().rolloverAll()
        TodoWidget().updateAll(context)
    }

    companion object {
        val INSTANCE_ID_KEY = ActionParameters.Key<Long>("instance_id")
    }
}
