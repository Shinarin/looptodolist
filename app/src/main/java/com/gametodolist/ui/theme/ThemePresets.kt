package com.gametodolist.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** 一套可切换的浅色主题预设；cardBorder 映射到 colorScheme.outlineVariant */
data class ThemePreset(
    val id: String,
    val label: String,
    val primary: Color,
    val onPrimaryContainer: Color,
    val primaryContainer: Color,
    val background: Color,
    val surfaceVariant: Color,
    val outline: Color,
    val cardBorder: Color,
    val gradientEnd: Color
)

const val DEFAULT_THEME_ID = "quest"

val THEME_PRESETS = listOf(
    ThemePreset("quest", "图鉴紫", Color(0xFF6C5CE7), Color(0xFF3A2E8F), Color(0xFFE7E2FF),
        Color(0xFFF6F4FF), Color(0xFFEFECFB), Color(0xFFD8D2F0), Color(0xFFECE8FB), Color(0xFF9D6CFF)),
    ThemePreset("mint", "薄荷绿", Color(0xFF4E8D6E), Color(0xFF0A2E1B), Color(0xFFD0EAD9),
        Color(0xFFF6F8F6), Color(0xFFE8EEE9), Color(0xFFC4D0C7), Color(0xFFE3EAE4), Color(0xFF7FB69E)),
    ThemePreset("sky", "晴空蓝", Color(0xFF4A8FE7), Color(0xFF0B3B78), Color(0xFFDFEBFC),
        Color(0xFFF4F7FD), Color(0xFFE9EFF9), Color(0xFFC6D5EA), Color(0xFFE3EAF7), Color(0xFF7FB3F0)),
    ThemePreset("sakura", "樱花粉", Color(0xFFE87A9A), Color(0xFF6E1E3C), Color(0xFFFCE3EB),
        Color(0xFFFDF4F7), Color(0xFFF9EAF0), Color(0xFFEACBD6), Color(0xFFF6E4EC), Color(0xFFF2A7C0)),
    ThemePreset("mikan", "蜜柑橙", Color(0xFFE8833A), Color(0xFF6B3405), Color(0xFFFCE8D8),
        Color(0xFFFDF7F1), Color(0xFFF8EFE6), Color(0xFFEAD6C4), Color(0xFFF5E8DA), Color(0xFFF2AC6E))
)

fun themePresetOf(id: String?): ThemePreset =
    THEME_PRESETS.firstOrNull { it.id == id } ?: THEME_PRESETS.first()

/** 当前主题预设（FAB 渐变等取 gradientEnd 用），由 GameTodoTheme provide */
val LocalThemePreset = staticCompositionLocalOf { THEME_PRESETS.first() }
