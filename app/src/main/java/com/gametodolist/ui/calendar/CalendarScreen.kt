package com.gametodolist.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gametodolist.domain.InstanceStatus
import com.gametodolist.ui.WEEKDAY_LABELS
import com.gametodolist.ui.components.EmptyState
import com.gametodolist.ui.components.LabelChip
import com.gametodolist.ui.remainingLabel
import com.gametodolist.ui.theme.CompletedGreen
import com.gametodolist.ui.theme.ExpiredRed
import com.gametodolist.ui.theme.UncategorizedGray
import com.gametodolist.ui.toEpochMillis
import java.time.LocalDate

@Composable
fun CalendarScreen(
    onEditTask: (Long) -> Unit,
    onAddTask: (Long) -> Unit,
    viewModel: CalendarViewModel = hiltViewModel()
) {
    val month by viewModel.month.collectAsStateWithLifecycle()
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val monthRenewals by viewModel.monthRenewals.collectAsStateWithLifecycle()
    val dayItems by viewModel.dayItems.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    fun colorOf(categoryId: Long?): Color =
        categories.firstOrNull { it.id == categoryId }?.color?.let(::Color) ?: UncategorizedGray

    // 更新日打点：日期 → 当天更新清单的分类色
    val dotsByDate: Map<LocalDate, List<Color>> = monthRenewals.mapValues { (_, categoryIds) ->
        categoryIds.map { colorOf(it) }
    }

    Scaffold(
        topBar = {
            // 紧凑标题栏，减少顶部留白
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "日历",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        floatingActionButton = {
            // 新增清单：编辑页默认日期 = 当前选中的日期
            FloatingActionButton(onClick = { onAddTask(selectedDate.toEpochMillis()) }) {
                Icon(Icons.Default.Add, contentDescription = "新增任务")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // 月份切换
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = viewModel::prevMonth, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上月")
                }
                Text(
                    "${month.year}年${month.monthValue}月",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                IconButton(onClick = viewModel::nextMonth, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下月")
                }
                TextButton(onClick = viewModel::goToToday) { Text("今天") }
            }

            // 星期表头
            Row(modifier = Modifier.fillMaxWidth()) {
                WEEKDAY_LABELS.forEach { label ->
                    Text(
                        "周$label",
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(2.dp))

            MonthGrid(
                month = month,
                selectedDate = selectedDate,
                dotsByDate = dotsByDate,
                onSelect = viewModel::selectDate
            )

            Spacer(Modifier.height(6.dp))
            Text(
                "${selectedDate.monthValue}月${selectedDate.dayOfMonth}日",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            // 当天更新的清单提醒
            val renewals = dayItems.filter { it.isRenewal }
            if (renewals.isNotEmpty()) {
                Text(
                    "🔄 当天更新：${renewals.joinToString("、") { it.item.template.title }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(4.dp))

            if (dayItems.isEmpty()) {
                EmptyState(text = "这一天没有任务")
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    // 底部留白避开悬浮添加按钮，防止遮住最后一项
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(dayItems, key = { it.item.template.id }) { dayItem ->
                        DayItemCard(
                            dayItem = dayItem,
                            selectedDate = selectedDate,
                            color = colorOf(dayItem.item.template.categoryId),
                            onClick = { onEditTask(dayItem.item.template.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthGrid(
    month: java.time.YearMonth,
    selectedDate: LocalDate,
    dotsByDate: Map<LocalDate, List<Color>>,
    onSelect: (LocalDate) -> Unit
) {
    val today = LocalDate.now()
    val firstOfMonth = month.atDay(1)
    // 周一开头的网格
    val offset = firstOfMonth.dayOfWeek.value - 1
    val gridStart = firstOfMonth.minusDays(offset.toLong())
    // 按当月实际跨度决定行数（5 或 6 行），避免日历过高挤压下方任务
    val weeks = (offset + month.lengthOfMonth() + 6) / 7

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(weeks) { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(7) { weekday ->
                    val date = gridStart.plusDays((week * 7 + weekday).toLong())
                    DayCell(
                        date = date,
                        inMonth = date.month == month.month,
                        isToday = date == today,
                        isSelected = date == selectedDate,
                        dots = dotsByDate[date].orEmpty(),
                        onSelect = onSelect,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    dots: List<Color>,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .aspectRatio(1.2f)
            .background(
                when {
                    isSelected -> MaterialTheme.colorScheme.primaryContainer
                    else -> Color.Transparent
                },
                MaterialTheme.shapes.small
            )
            .clickable { onSelect(date) }
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = when {
                isToday -> MaterialTheme.colorScheme.primary
                !inMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            dots.take(3).forEach { color ->
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .background(color, CircleShape)
                )
            }
        }
    }
}

@Composable
private fun DayItemCard(
    dayItem: CalendarDayItem,
    selectedDate: LocalDate,
    color: Color,
    onClick: () -> Unit
) {
    val completed = dayItem.status == InstanceStatus.COMPLETED.name
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(color, CircleShape)
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    dayItem.item.template.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        textDecoration = if (completed) TextDecoration.LineThrough else null
                    ),
                    color = if (completed) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (dayItem.isRenewal) {
                    Spacer(Modifier.height(3.dp))
                    LabelChip("今日更新", MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(8.dp))
            // 右侧：剩余天数；已完成/已作废显示状态
            when (dayItem.status) {
                InstanceStatus.COMPLETED.name ->
                    LabelChip("已完成", CompletedGreen)
                InstanceStatus.EXPIRED.name ->
                    LabelChip("已作废", ExpiredRed)
                else ->
                    Text(
                        remainingLabel(
                            dayItem.windowStart,
                            dayItem.windowEnd,
                            selectedDate.toEpochMillis()
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = color
                    )
            }
        }
    }
}
