package com.gametodolist.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gametodolist.data.TemplateWithInstance
import com.gametodolist.domain.RecurrenceType
import com.gametodolist.domain.toRecurrenceType
import com.gametodolist.ui.cooldownLabel
import com.gametodolist.ui.recurrenceLabel
import com.gametodolist.ui.remainingDays
import com.gametodolist.ui.remainingLabel
import com.gametodolist.ui.theme.CompletedGreen

/** 任务卡片：分类色条 + 完成控件 + 标题/备注 + 状态胶囊；完成或停用时标题灰化划线 */
@Composable
fun TaskCard(
    item: TemplateWithInstance,
    categoryColor: Color,
    onComplete: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    completed: Boolean = false,
    paused: Boolean = false
) {
    val template = item.template
    val instance = item.activeInstance

    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = MaterialTheme.shapes.medium,
                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
            )
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box {
            // 左侧 6dp 渐变色条，贴卡片左缘
            val barColor = when {
                paused -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                completed -> categoryColor.copy(alpha = 0.45f)
                else -> categoryColor
            }
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(6.dp)
                    .height(44.dp)
                    .background(
                        Brush.verticalGradient(listOf(barColor, barColor.copy(alpha = 0.55f))),
                        RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)
                    )
            )
            Row(
                modifier = Modifier.padding(start = 18.dp, top = 12.dp, end = 14.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 停用时完成控件不可点；已完成=绿底白勾，未完成=分类色描边空心圆
                if (paused) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .border(
                                2.5.dp,
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                CircleShape
                            )
                    )
                } else if (completed) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(CompletedGreen, CircleShape)
                            .clickable(
                                onClickLabel = "撤销完成",
                                role = androidx.compose.ui.semantics.Role.Checkbox,
                                onClick = onComplete
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .border(2.5.dp, categoryColor, CircleShape)
                            .clickable(
                                onClickLabel = "完成",
                                role = androidx.compose.ui.semantics.Role.Checkbox,
                                onClick = onComplete
                            )
                    )
                }
                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = template.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            textDecoration = if (completed || paused) TextDecoration.LineThrough else null
                        ),
                        color = if (completed || paused) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (template.note.isNotBlank()) {
                        Text(
                            text = template.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (template.recurrenceType != RecurrenceType.NONE.name) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = template.recurrenceLabel(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 停用优先于实例状态；其余显示完成、冷却或剩余时间
                if (paused) {
                    Spacer(Modifier.width(10.dp))
                    RemainingChip("停用", MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (instance != null) {
                    Spacer(Modifier.width(10.dp))
                    if (completed) {
                        val cooling = if (template.recurrenceType.toRecurrenceType() ==
                            RecurrenceType.AFTER_COMPLETION
                        ) cooldownLabel(instance.windowEnd) else null
                        RemainingChip(cooling ?: "已完成", CompletedGreen)
                    } else {
                        val (fg, bg) = remainingChipColors(
                            remainingDays(instance.windowStart, instance.windowEnd),
                            categoryColor
                        )
                        RemainingChip(
                            remainingLabel(instance.windowStart, instance.windowEnd),
                            fg, bg
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LabelChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.14f),
        shape = CircleShape
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "🌿", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
