package com.gametodolist.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState

/** 数据变更后刷新所有桌面小组件 */
object WidgetUpdater {

    /**
     * 刷新流程：先 bump Glance Preferences 里的数据版本号，再触发 update。
     *
     * 注意（Glance 1.1.1 行为）：update() 只是给会话发事件，仅当 Glance 状态内容
     * 发生变化才会重组；而 provideGlance 每个会话只执行一次，Room 数据的变化本身
     * 不会驱动重组——会话存活期（约几十秒）内的刷新会被静默忽略，表现为
     * "点了没反应/要多点几次"。bump 版本号后重组必然发生，produceState 随之重读数据。
     */
    suspend fun refresh(context: Context) {
        runCatching {
            val manager = GlanceAppWidgetManager(context)
            manager.getGlanceIds(TodoWidget::class.java).forEach { glanceId ->
                updateAppWidgetState(context, glanceId) { prefs ->
                    prefs[KEY_DATA_VERSION] = System.currentTimeMillis()
                }
                TodoWidget().update(context, glanceId)
            }
        }.onFailure { Log.w(TAG, "widget update failed", it) }
    }

    private const val TAG = "WidgetUpdater"
}
