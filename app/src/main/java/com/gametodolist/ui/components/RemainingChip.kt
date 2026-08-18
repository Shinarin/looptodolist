package com.gametodolist.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gametodolist.ui.theme.ExpiredRed
import com.gametodolist.ui.theme.UrgentOrange
import com.gametodolist.ui.theme.UrgentOrangeDark

/** 剩余天数胶囊配色：已截止红、<=2 天紧急橙、其余分类色；返回 文字色 to 底色 */
fun remainingChipColors(days: Long?, categoryColor: Color): Pair<Color, Color> = when {
    days == null -> categoryColor to categoryColor.copy(alpha = 0.12f)
    days < 0 -> ExpiredRed to ExpiredRed.copy(alpha = 0.12f)
    days <= 2 -> UrgentOrangeDark to UrgentOrange.copy(alpha = 0.20f)
    else -> categoryColor to categoryColor.copy(alpha = 0.12f)
}

/** 剩余天数胶囊：浅色版——浅色底 + 强调色文字，全圆角 */
@Composable
fun RemainingChip(text: String, color: Color, container: Color = color.copy(alpha = 0.12f)) {
    Surface(color = container, shape = CircleShape) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
        )
    }
}
