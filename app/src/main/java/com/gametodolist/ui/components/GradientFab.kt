package com.gametodolist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.gametodolist.ui.theme.LocalThemePreset

/** 渐变 FAB：主色→预设渐变尾色，带主色光晕；主页/日历页共用 */
@Composable
fun GradientFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val glow = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .size(56.dp)
            .shadow(10.dp, RoundedCornerShape(20.dp), spotColor = glow.copy(alpha = 0.35f), ambientColor = glow.copy(alpha = 0.35f))
            .background(
                Brush.linearGradient(listOf(glow, LocalThemePreset.current.gradientEnd)),
                RoundedCornerShape(20.dp)
            )
            .clickable(onClickLabel = "新增任务", role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
    }
}
