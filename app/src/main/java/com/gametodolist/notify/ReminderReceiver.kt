package com.gametodolist.notify

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gametodolist.GameTodoApp
import com.gametodolist.R
import com.gametodolist.domain.InstanceStatus
import com.gametodolist.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 闹钟触发：先校验实例仍 ACTIVE 且模板未停用，再弹出到期提醒通知 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val instanceId = intent.getLongExtra(EXTRA_INSTANCE_ID, -1L)
        if (instanceId == -1L) return
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val body = intent.getStringExtra(EXTRA_BODY)?.takeIf { it.isNotBlank() } ?: DEFAULT_BODY

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val entryPoint = ReminderEntryPoint.get(context)
                val instance = entryPoint.instanceDao().getById(instanceId)
                val template = instance?.let { entryPoint.templateDao().getById(it.templateId) }
                if (instance == null || instance.status != InstanceStatus.ACTIVE.name ||
                    template == null || template.paused
                ) {
                    // 实例已结束或模板已停用：清理残留闹钟，不弹通知
                    entryPoint.reminderScheduler().cancel(instanceId)
                    return@launch
                }
                showNotification(context, instanceId, title, body)
            } catch (e: Exception) {
                android.util.Log.w("ReminderReceiver", "check instance failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showNotification(context: Context, instanceId: Long, title: String, body: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val openApp = PendingIntent.getActivity(
            context, instanceId.toInt(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, GameTodoApp.REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(context).notify(instanceId.toInt(), notification)
    }

    companion object {
        const val EXTRA_INSTANCE_ID = "extra_instance_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_BODY = "extra_body"
        const val DEFAULT_BODY = "任务即将截止，记得完成哦"
    }
}
