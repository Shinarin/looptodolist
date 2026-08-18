package com.gametodolist.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private fun lightColorsOf(p: ThemePreset) = lightColorScheme(
    primary = p.primary,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = p.primaryContainer,
    onPrimaryContainer = p.onPrimaryContainer,
    secondary = QuestSecondary,
    secondaryContainer = p.surfaceVariant,
    onSecondaryContainer = p.onPrimaryContainer,
    tertiary = UrgentOrange,
    background = p.background,
    onBackground = AppOnBackground,
    surface = AppSurface,
    onSurface = AppOnSurface,
    // M3 1.2+ 底部导航/弹窗默认用 surfaceContainer，缺省值偏紫，显式设为白
    surfaceContainer = AppSurface,
    surfaceVariant = p.surfaceVariant,
    onSurfaceVariant = AppOnSurfaceVariant,
    outline = p.outline,
    outlineVariant = p.cardBorder
)

// 深色兜底：保持可读即可，主打浅色预设
private val DarkColors = darkColorScheme()

val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(18.dp),  // 卡片圆角 16 -> 18
    large = RoundedCornerShape(22.dp)
)

@Composable
fun GameTodoTheme(
    themeId: String = DEFAULT_THEME_ID,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val preset = themePresetOf(themeId)
    CompositionLocalProvider(LocalThemePreset provides preset) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else lightColorsOf(preset),
            shapes = AppShapes,
            typography = Typography(),
            content = content
        )
    }
}
