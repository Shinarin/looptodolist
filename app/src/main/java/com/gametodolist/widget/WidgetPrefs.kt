package com.gametodolist.widget

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * 桌面小组件外观设置（SharedPreferences 持久化）：
 * 背景模式：纯色（可调透明度）/ 壁纸（可调亮度、按组件实例裁剪）
 * 另存小组件交互状态（已完成区是否展开）与每个组件实例的壁纸裁剪区域。
 */
object WidgetPrefs {
    private const val NAME = "widget_prefs"

    const val BG_SOLID = 0
    const val BG_WALLPAPER = 2

    const val KEY_BG_MODE = "bg_mode"
    const val KEY_SOLID_ALPHA = "solid_alpha"     // 20..100（%），默认 100
    const val KEY_WALLPAPER_DIM = "wallpaper_dim" // 0..60（% 变暗），默认 30

    const val WALLPAPER_FILE = "widget_wallpaper.jpg"

    private const val KEY_CROP_PREFIX = "crop_rect_" // + appWidgetId + _l/_t/_r/_b（千分比）

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 背景模式；历史遗留的磨砂(1)按纯色处理 */
    fun bgMode(context: Context): Int =
        prefs(context).getInt(KEY_BG_MODE, BG_SOLID).let { if (it == BG_WALLPAPER) BG_WALLPAPER else BG_SOLID }

    fun solidAlpha(context: Context): Float = prefs(context).getInt(KEY_SOLID_ALPHA, 100) / 100f
    fun wallpaperDim(context: Context): Float = prefs(context).getInt(KEY_WALLPAPER_DIM, 30) / 100f

    /** 某组件实例的壁纸裁剪区域（源图 0..1 比例），null = 未自定义（居中裁剪） */
    fun cropRect(context: Context, appWidgetId: Int): FloatArray? {
        val p = prefs(context)
        val base = KEY_CROP_PREFIX + appWidgetId
        if (!p.contains(base + "_l")) return null
        return floatArrayOf(
            p.getInt(base + "_l", 0) / 1000f,
            p.getInt(base + "_t", 0) / 1000f,
            p.getInt(base + "_r", 1000) / 1000f,
            p.getInt(base + "_b", 1000) / 1000f
        )
    }

    fun setCropRect(context: Context, appWidgetId: Int, l: Float, t: Float, r: Float, b: Float) =
        prefs(context).edit()
            .putInt(KEY_CROP_PREFIX + appWidgetId + "_l", (l.coerceIn(0f, 1f) * 1000).toInt())
            .putInt(KEY_CROP_PREFIX + appWidgetId + "_t", (t.coerceIn(0f, 1f) * 1000).toInt())
            .putInt(KEY_CROP_PREFIX + appWidgetId + "_r", (r.coerceIn(0f, 1f) * 1000).toInt())
            .putInt(KEY_CROP_PREFIX + appWidgetId + "_b", (b.coerceIn(0f, 1f) * 1000).toInt())
            .apply()

    /** 组件实例删除后清理其裁剪区域键，避免长期残留 */
    fun clearCropRect(context: Context, appWidgetId: Int) {
        val base = KEY_CROP_PREFIX + appWidgetId
        prefs(context).edit()
            .remove(base + "_l").remove(base + "_t")
            .remove(base + "_r").remove(base + "_b")
            .apply()
    }

    /** 更换壁纸后清空所有裁剪区域（区域是相对旧图的相对值，对新图无意义） */
    fun clearAllCropRects(context: Context) {
        val editor = prefs(context).edit()
        prefs(context).all.keys.filter { it.startsWith(KEY_CROP_PREFIX) }.forEach { editor.remove(it) }
        editor.apply()
    }

    /** 任一组件实例的裁剪区域（设置页预览与组件保持一致用） */
    fun anyCropRect(context: Context): FloatArray? {
        val key = prefs(context).all.keys
            .firstOrNull { it.startsWith(KEY_CROP_PREFIX) && it.endsWith("_l") } ?: return null
        val id = key.removePrefix(KEY_CROP_PREFIX).removeSuffix("_l").toIntOrNull() ?: return null
        return cropRect(context, id)
    }

    fun wallpaperFile(context: Context): File = File(context.filesDir, WALLPAPER_FILE)

    fun hasWallpaper(context: Context): Boolean = wallpaperFile(context).let { it.exists() && it.length() > 0 }
}
