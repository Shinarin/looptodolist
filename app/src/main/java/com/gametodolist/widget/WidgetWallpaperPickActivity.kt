package com.gametodolist.widget

import android.appwidget.AppWidgetManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * 更换壁纸中转页（无界面，半透明）：组件头部按钮 / App 设置页共用入口。
 * 流程：拉起系统选图 → 解码保存到挂起文件 → 直接跳裁剪页（保存才生效，取消则旧壁纸原样保留）。
 * 桌面没有组件实例时无需裁剪，挂起文件直接生效并刷新。
 *
 * 独立 taskAffinity + excludeFromRecents：从组件进入时不沾 App 主任务栈，裁剪完返回直接回桌面；
 * 从设置页进入（无 NEW_TASK）则落在主任务栈内，裁剪完返回设置页。
 */
class WidgetWallpaperPickActivity : ComponentActivity() {

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) {
            finish() // 取消选图：无事发生
        } else {
            lifecycleScope.launch { onPicked(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WallpaperImporter.discardPending(this) // 清上次可能残留的挂起文件
        // 防旋转重建时重复拉起选择器
        if (savedInstanceState == null) {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    private suspend fun onPicked(uri: Uri) {
        if (!WallpaperImporter.saveToPending(this, uri)) {
            Toast.makeText(this, "壁纸读取失败，请换一张试试", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val appWidgetId = resolveTargetWidgetId()
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            // 桌面没有组件：无可裁剪对象，直接生效
            WallpaperImporter.commitPending(this)
            runCatching { WidgetUpdater.refresh(this) }
        } else {
            startActivity(
                android.content.Intent(this, WidgetCropActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    .putExtra(WidgetCropActivity.EXTRA_PENDING, true)
            )
        }
        finish()
    }

    /** 裁剪目标：intent 指定的组件实例（组件头部进入），否则取桌面上第一个实例；一个都没有返回 INVALID */
    private suspend fun resolveTargetWidgetId(): Int {
        val fromIntent = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        if (fromIntent != AppWidgetManager.INVALID_APPWIDGET_ID) return fromIntent
        val manager = GlanceAppWidgetManager(this)
        val glanceId = manager.getGlanceIds(TodoWidget::class.java).firstOrNull()
            ?: return AppWidgetManager.INVALID_APPWIDGET_ID
        return runCatching { manager.getAppWidgetId(glanceId) }
            .getOrDefault(AppWidgetManager.INVALID_APPWIDGET_ID)
    }
}
