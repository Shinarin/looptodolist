package com.gametodolist.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gametodolist.data.SettingsPrefs
import com.gametodolist.notify.ReminderScheduler
import com.gametodolist.widget.WidgetPrefs
import com.gametodolist.widget.WidgetUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class WidgetSettingsUiState(
    val bgMode: Int = WidgetPrefs.BG_SOLID,
    val solidAlpha: Int = 100,     // 20..100 %
    val wallpaperDim: Int = 30,    // 0..60 %
    val hasWallpaper: Boolean = false,
    /** 任一组件实例的壁纸裁剪区域（0..1 比例），用于预览与组件保持一致；null = 居中裁剪 */
    val cropRect: List<Float>? = null,
    val exactAlarmGranted: Boolean = true,
    val wallpaperError: Boolean = false,
    /** 删除清单前是否弹确认警示 */
    val deleteConfirmEnabled: Boolean = true,
    /** 主题预设 id（"" = 默认图鉴紫） */
    val themeId: String = ""
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reminderScheduler: ReminderScheduler,
    private val settingsPrefs: SettingsPrefs
) : ViewModel() {

    private val _uiState = MutableStateFlow(loadState())
    val uiState: StateFlow<WidgetSettingsUiState> = _uiState.asStateFlow()

    /** 壁纸位图（设置页预览用），单独持有，避免拖滑块时反复解码 */
    private val _wallpaper = MutableStateFlow<Bitmap?>(null)
    val wallpaper: StateFlow<Bitmap?> = _wallpaper.asStateFlow()

    init {
        viewModelScope.launch {
            val bmp = decodeWallpaper()
            _wallpaper.value = bmp
            if (WidgetPrefs.hasWallpaper(context) && bmp == null) {
                _uiState.update { it.copy(wallpaperError = true) }
            }
        }
    }

    private fun loadState() = WidgetSettingsUiState(
        bgMode = WidgetPrefs.bgMode(context),
        solidAlpha = WidgetPrefs.prefs(context).getInt(WidgetPrefs.KEY_SOLID_ALPHA, 100),
        wallpaperDim = WidgetPrefs.prefs(context).getInt(WidgetPrefs.KEY_WALLPAPER_DIM, 30),
        hasWallpaper = WidgetPrefs.hasWallpaper(context),
        cropRect = WidgetPrefs.anyCropRect(context)?.toList(),
        exactAlarmGranted = reminderScheduler.canScheduleExactReminders(),
        deleteConfirmEnabled = settingsPrefs.deleteConfirmEnabled.value,
        themeId = settingsPrefs.themeId.value
    )

    /** 主题颜色切换（设置页色板） */
    fun setThemeId(id: String) {
        settingsPrefs.setThemeId(id)
        _uiState.update { it.copy(themeId = id) }
    }

    /** 删除清单前是否弹确认警示 */
    fun setDeleteConfirmEnabled(enabled: Boolean) {
        settingsPrefs.setDeleteConfirmEnabled(enabled)
        _uiState.update { it.copy(deleteConfirmEnabled = enabled) }
    }

    /** 从系统设置页返回后刷新精确闹钟授权状态 */
    fun refreshExactAlarmState() {
        _uiState.update { it.copy(exactAlarmGranted = reminderScheduler.canScheduleExactReminders()) }
    }

    private suspend fun decodeWallpaper(): Bitmap? = withContext(Dispatchers.IO) {
        if (WidgetPrefs.hasWallpaper(context)) {
            runCatching { BitmapFactory.decodeFile(WidgetPrefs.wallpaperFile(context).absolutePath) }.getOrNull()
        } else null
    }

    /** 仅落盘并更新界面状态（滑块拖动过程中调用，不刷新小组件） */
    private fun persist(key: String, value: Int) {
        WidgetPrefs.prefs(context).edit().putInt(key, value).apply()
        _uiState.update { loadState() }
    }

    /** 把当前设置同步到桌面小组件（滑块松手 / 模式切换时调用） */
    fun refreshWidget() {
        viewModelScope.launch { WidgetUpdater.refresh(context) }
    }

    fun setBgMode(mode: Int) {
        persist(WidgetPrefs.KEY_BG_MODE, mode)
        refreshWidget()
    }

    fun setSolidAlpha(percent: Int) = persist(WidgetPrefs.KEY_SOLID_ALPHA, percent.coerceIn(20, 100))
    fun setWallpaperDim(percent: Int) = persist(WidgetPrefs.KEY_WALLPAPER_DIM, percent.coerceIn(0, 60))

    /**
     * 从外部换壁纸流程（中转页 → 裁剪页，暂存式提交）返回后刷新预览与界面状态。
     * 壁纸保存/清裁剪区域/切模式都在 WallpaperImporter 与裁剪页里完成，这里只重读落盘结果。
     */
    fun refreshWallpaperState() {
        viewModelScope.launch {
            _wallpaper.value = decodeWallpaper()
            _uiState.update { loadState() }
        }
    }

    fun clearWallpaper() {
        WidgetPrefs.wallpaperFile(context).delete()
        WidgetPrefs.prefs(context).edit()
            .putInt(WidgetPrefs.KEY_BG_MODE, WidgetPrefs.BG_SOLID).apply()
        _uiState.update { loadState() }
        _wallpaper.value = null
        refreshWidget()
    }
}
