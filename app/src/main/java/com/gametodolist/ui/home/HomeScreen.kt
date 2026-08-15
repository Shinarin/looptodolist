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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gametodolist.data.CategoryGroup
import com.gametodolist.data.TemplateWithInstance
import com.gametodolist.ui.components.EmptyState
import com.gametodolist.ui.components.LabelChip
import com.gametodolist.ui.components.TaskCard
import com.gametodolist.ui.theme.CompletedGreen
import com.gametodolist.ui.theme.ExpiredRed
import com.gametodolist.ui.theme.UncategorizedGray

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onEditTask: (Long?) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val homeData by viewModel.homeData.collectAsStateWithLifecycle()
    val collapsed by viewModel.collapsed.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<TemplateWithInstance?>(null) }
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
        val data = homeData
        if (data == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        if (data.activeGroups.isEmpty() && data.completedGroups.isEmpty() && data.expiredGroups.isEmpty()) {
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
            // 底部留白避开悬浮添加按钮，防止遮住最后一项
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ---------- 未完成区 ----------
            sectionHeader("未完成", data.activeGroups.sumOf { it.items.size })
            data.activeGroups.forEach { group ->
                categorySection(
                    sectionKey = "active",
                    group = group,
                    collapsed = collapsed,
                    onToggle = viewModel::toggleCollapsed,
                    completed = false,
                    onComplete = { viewModel.complete(it) },
                    onDelete = { pendingDelete = it },
                    onClick = { onEditTask(it.template.id) }
                )
            }

            // ---------- 已完成区（为空时不显示） ----------
            if (data.completedGroups.isNotEmpty()) {
                item(key = "completed_divider") { Spacer(Modifier.padding(top = 8.dp)) }
                sectionHeader("已完成", data.completedGroups.sumOf { it.items.size })
                data.completedGroups.forEach { group ->
                    categorySection(
                        sectionKey = "completed",
                        group = group,
                        collapsed = collapsed,
                        onToggle = viewModel::toggleCollapsed,
                        completed = true,
                        onComplete = { viewModel.uncomplete(it) },
                        onDelete = { pendingDelete = it },
                        onClick = { onEditTask(it.template.id) }
                    )
                }
            }

            // ---------- 已作废区（为空时不显示） ----------
            if (data.expiredGroups.isNotEmpty()) {
                item(key = "expired_divider") { Spacer(Modifier.padding(top = 8.dp)) }
                sectionHeader("已作废", data.expiredGroups.sumOf { it.items.size })
                data.expiredGroups.forEach { group ->
                    group.items.forEach { item ->
                        item(key = "expired_${group.category?.id ?: -1L}_${item.template.id}") {
                            SwipeableExpiredCard(
                                item = item,
                                color = group.color(),
                                onRevive = { viewModel.revive(item) },
                                onRequestDelete = { pendingDelete = item },
                                onClick = { onEditTask(item.template.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    // 删除确认弹窗：左滑只弹确认，确认后才真正删除
    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除清单？") },
            text = { Text("「${item.template.title}」及其历史记录、提醒将一并删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(item)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
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
    collapsed: Set<String>,
    onToggle: (String) -> Unit,
    completed: Boolean,
    onComplete: (TemplateWithInstance) -> Unit,
    onDelete: (TemplateWithInstance) -> Unit,
    onClick: (TemplateWithInstance) -> Unit
) {
    val key = "$sectionKey:${group.category?.id ?: -1L}"
    val isCollapsed = key in collapsed

    item(key = "header_$key") {
        CategoryHeader(group = group, collapsed = isCollapsed, onToggle = { onToggle(key) })
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
                        onRequestDelete = { onDelete(item) },
                        onClick = { onClick(item) }
                    )
                }
            }
        }
    }
}

private fun CategoryGroup.color(): Color =
    category?.color?.let { Color(it) } ?: UncategorizedGray

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
    onRequestDelete: () -> Unit,
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
                // 左滑删除：只通知外部弹确认窗，不直接 dismiss
                SwipeToDismissBoxValue.EndToStart -> { onRequestDelete(); false }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = !completed,
        backgroundContent = {
            val (bg, label) = when (dismissState.targetValue) {
                SwipeToDismissBoxValue.StartToEnd -> CompletedGreen to "完成"
                SwipeToDismissBoxValue.EndToStart -> ExpiredRed to "删除"
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableExpiredCard(
    item: TemplateWithInstance,
    color: Color,
    onRevive: () -> Unit,
    onRequestDelete: () -> Unit,
    onClick: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            // 仅左滑删除：只通知外部弹确认窗，不直接 dismiss；右滑不响应
            if (value == SwipeToDismissBoxValue.EndToStart) onRequestDelete()
            false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val deleting = dismissState.targetValue == SwipeToDismissBoxValue.EndToStart
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (deleting) ExpiredRed else Color.Transparent,
                        MaterialTheme.shapes.medium
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (deleting) {
                    Text("删除", color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    ) {
        ExpiredTaskCard(item = item, color = color, onRevive = onRevive, onClick = onClick)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpiredTaskCard(
    item: TemplateWithInstance,
    color: Color,
    onRevive: () -> Unit,
    onClick: () -> Unit
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(40.dp)
                    .background(color.copy(alpha = 0.45f), MaterialTheme.shapes.small)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.template.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        textDecoration = TextDecoration.LineThrough
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                LabelChip("已截止作废", MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onRevive) { Text("重新开启") }
        }
    }
}
