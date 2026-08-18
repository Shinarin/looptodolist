package com.gametodolist.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 应用级偏好设置（SharedPreferences "app_settings"），区别于小组件外观的 WidgetPrefs */
@Singleton
class SettingsPrefs @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _deleteConfirmEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_DELETE_CONFIRM_ENABLED, true)
    )

    /** 删除清单前是否弹确认警示（弹窗里勾选「不再提醒」或设置页均可关闭） */
    val deleteConfirmEnabled: StateFlow<Boolean> = _deleteConfirmEnabled.asStateFlow()

    fun setDeleteConfirmEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DELETE_CONFIRM_ENABLED, enabled).apply()
        _deleteConfirmEnabled.value = enabled
    }

    private val _themeId = MutableStateFlow(prefs.getString(KEY_THEME_ID, "") ?: "")

    /** 主题预设 id（"" = 默认图鉴紫），设置页色板切换 */
    val themeId: StateFlow<String> = _themeId.asStateFlow()

    fun setThemeId(id: String) {
        prefs.edit().putString(KEY_THEME_ID, id).apply()
        _themeId.value = id
    }

    companion object {
        private const val FILE = "app_settings"
        private const val KEY_DELETE_CONFIRM_ENABLED = "delete_confirm_enabled"
        private const val KEY_THEME_ID = "theme_id"
    }
}
