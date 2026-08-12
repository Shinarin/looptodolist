package com.gametodolist.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gametodolist.data.CategoryGroup
import com.gametodolist.data.TemplateWithInstance
import com.gametodolist.ui.components.EmptyState
import com.gametodolist.ui.components.TaskCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onEditTask: (Long?) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val homeData by viewModel.homeData.collectAsStateWithLifecycle()
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }

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
                    "循环清单",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onEditTask(null) }) {
                Icon(Icons.Default.Add, contentDescription = "新增任务")
            }
        }
    ) { padding ->
        if (homeData.activeGroups.isEmpty() && homeData.completedGroups.isEmpty()) {
            EmptyState(
                text = "还没有清单，点右下角 + 创建第一条吧",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ---------- 未完成区 ----------
            sectionHeader("未完成", homeData.activeGroups.sumOf { it.items.size })
            homeData.activeGroups.forEach { group ->
                categorySection(
                    sectionKey = "active",
                    group = group,
                    collapsed = collapsed,
                    completed = false,
                    onComplete = { viewModel.complete(it) },
                    onDelete = { viewModel.delete(it) },
                    onClick = { onEditTask(it.template.id) }
                )
            }

            // ---------- 已完成区（为空时不显示） ----------
            if (homeData.completedGroups.isNotEmpty()) {
                item(key = "completed_divider") { Spacer(Modifier.padding(top = 8.dp)) }
                sectionHeader("已完成", homeData.completedGroups.sumOf { it.items.size })
                homeData.completedGroups.forEach { group ->
                    categorySection(
                        sectionKey = "completed",
                        group = group,
                        collapsed = collapsed,
                        completed = true,
                        onComplete = { viewModel.uncomplete(it) },
                        onDelete = { viewModel.delete(it) },
                        onClick = { onEditTask(it.template.id) }
                    )
                }
            }
        }
    }
}

private fun LazyListScope.sectionHeader(title: String, count: Int) {
    item(key = "section_$title") {
        Text(
            text = "$title · $count",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

private fun LazyListScope.categorySection(
    sectionKey: String,
    group: CategoryGroup,
    collapsed: MutableMap<String, Boolean>,
    completed: Boolean,
    onComplete: (TemplateWithInstance) -> Unit,
    onDelete: (TemplateWithInstance) -> Unit,
    onClick: (TemplateWithInstance) -> Unit
) {
    val key = "$sectionKey:${group.category?.id ?: -1L}"
    val isCollapsed = collapsed[key] == true

    item(key = "header_$key") {
        CategoryHeader(
            group = group,
            collapsed = isCollapsed,
            onToggle = { collapsed[key] = !isCollapsed }
        )
    }

    item(key = "items_$key") {
        AnimatedVisibility(
            visible = !isCollapsed,
            enter = expandVertically(tween(150)) + fadeIn(tween(150)),
            exit = shrinkVertically(tween(130)) + fadeOut(tween(110))
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                group.items.forEach { item ->
                    SwipeableTaskCard(
                        item = item,
                        color = group.color(),
                        completed = completed,
                        onComplete = { onComplete(item) },
                        onDelete = { onDelete(item) },
                        onClick = { onClick(item) }
                    )
                }
            }
        }
    }
}

private fun CategoryGroup.color(): Color =
    category?.color?.let { Color(it) } ?: Color(0xFF9E9E9E)

@Composable
private fun CategoryHeader(
    group: CategoryGroup,
    collapsed: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(group.color(), CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = group.category?.let { "${it.icon} ${it.name}".trim() } ?: "未分类",
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${group.items.size}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = if (collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableTaskCard(
    item: TemplateWithInstance,
    color: Color,
    completed: Boolean,
    onComplete: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                // 右滑完成仅对未完成区有效；已完成区右滑不响应
                SwipeToDismissBoxValue.StartToEnd -> {
                    if (!completed) onComplete()
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> { onDelete(); true }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = !completed,
        backgroundContent = {
            val (bg, label) = when (dismissState.targetValue) {
                SwipeToDismissBoxValue.StartToEnd -> Color(0xFF7FB69E) to "完成"
                SwipeToDismissBoxValue.EndToStart -> Color(0xFFB45A5A) to "删除"
                SwipeToDismissBoxValue.Settled -> Color.Transparent to ""
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bg, MaterialTheme.shapes.medium)
                    .padding(horizontal = 20.dp),
                contentAlignment = if (dismissState.targetValue == SwipeToDismissBoxValue.StartToEnd)
                    Alignment.CenterStart else Alignment.CenterEnd
            ) {
                Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
    ) {
        TaskCard(
            item = item,
            categoryColor = color,
            onComplete = onComplete,
            onClick = onClick,
            completed = completed
        )
    }
}
