package com.gametodolist.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 壁纸导入（暂存式提交）：
 * 选图后先解码保存到「挂起文件」，不碰当前壁纸/裁剪区域/背景模式；
 * 裁剪页点保存才 commitPending 原子生效，取消则 discardPending 丢弃，旧壁纸原样保留。
 *
 * 保存规格：按设备屏幕长边封顶（1440..3200px）的 JPEG q92——
 * 组件最大不超过屏幕，再大只费内存/存储；先读边界按 2 的幂采样再解码，防超大原图 OOM。
 */
object WallpaperImporter {

    private const val PENDING_FILE = "widget_wallpaper_pending.jpg"

    fun pendingFile(context: Context): File = File(context.filesDir, PENDING_FILE)

    /** 壁纸保存上限：屏幕长边像素，夹在 1440..3200 */
    private fun targetPx(context: Context): Int {
        val dm = context.resources.displayMetrics
        return maxOf(dm.widthPixels, dm.heightPixels).coerceIn(1440, 3200)
    }

    /** 解码 uri → 采样缩放 → 保存到挂起文件。成功返回 true（失败时挂起文件可能不存在） */
    suspend fun saveToPending(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            pendingFile(context).delete() // 清掉上次可能残留的挂起文件
            val target = targetPx(context)
            // 边界探测：确定原图尺寸以计算采样率
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            val srcLong = maxOf(bounds.outWidth, bounds.outHeight)
            if (srcLong <= 0) return@runCatching false
            // 采样到「不小于目标尺寸」的最大 2 的幂档位，控制解码内存
            var sample = 1
            while (srcLong / (sample * 2) >= target) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            } ?: return@runCatching false
            val scale = minOf(1f, target.toFloat() / maxOf(decoded.width, decoded.height))
            val bitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt(),
                    (decoded.height * scale).toInt(),
                    true
                )
            } else decoded
            pendingFile(context).outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            true
        }.getOrDefault(false)
    }

    /**
     * 挂起壁纸生效：原子替换正式文件 → 清空所有旧裁剪区域（相对旧图无意义）→ 切壁纸模式。
     * 注意会清空裁剪区域，调用方若有新区域要在本调用之后再写。
     */
    fun commitPending(context: Context): Boolean {
        val pending = pendingFile(context)
        if (!pending.exists() || pending.length() <= 0) return false
        val dest = WidgetPrefs.wallpaperFile(context)
        dest.delete()
        if (!pending.renameTo(dest)) return false
        WidgetPrefs.clearAllCropRects(context)
        WidgetPrefs.prefs(context).edit()
            .putInt(WidgetPrefs.KEY_BG_MODE, WidgetPrefs.BG_WALLPAPER).apply()
        return true
    }

    /** 丢弃挂起文件（取消裁剪 / 中转页启动时清理残留） */
    fun discardPending(context: Context) {
        pendingFile(context).delete()
    }
}
