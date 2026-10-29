package com.gametodolist.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gametodolist.data.CategoryGroup
import com.gametodolist.data.TemplateWithInstance
import com.gametodolist.ui.components.EmptyState
import com.gametodolist.ui.components.GradientFab
import com.gametodolist.ui.components.LabelChip
import com.gametodolist.ui.components.TaskCard
import com.gametodolist.ui.theme.ExpiredRed
import com.gametodolist.ui.theme.UncategorizedGray
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onEditTask: (Long?) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val homeData by viewModel.homeData.collectAsStateWithLifecycle()
    val collapsed by viewModel.collapsed.collectAsStateWithLifecycle()
    val deleteConfirmEnabled by viewModel.deleteConfirmEnabled.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<TemplateWithInstance?>(null) }
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    // 左滑点「删除」后的处理：开了警示则弹确认，关了（不再提醒）直接删
    fun requestDelete(item: TemplateWithInstance) {
        if (deleteConfirmEnabled) pendingDelete = item else viewModel.delete(item)
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
            GradientFab(onClick = { onEditTask(null) })
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
            sectionHeader("未完成", data.activeGroups.sumOf { it.items.size }, "项待办")
            data.activeGroups.forEach { group ->
                categorySection(
                    sectionKey = "active",
                    group = group,
                    collapsed = collapsed,
                    onToggle = viewModel::toggleCollapsed,
                    completed = false,
                    onComplete = { viewModel.complete(it) },
                    onDelete = ::requestDelete,
                    onClick = { onEditTask(it.template.id) }
                )
            }

            // ---------- 已完成区（为空时不显示） ----------
            if (data.completedGroups.isNotEmpty()) {
                item(key = "completed_divider", contentType = "divider") {
                    Spacer(Modifier.padding(top = 8.dp))
                }
                sectionHeader("已完成", data.completedGroups.sumOf { it.items.size }, "项")
                data.completedGroups.forEach { group ->
                    categorySection(
                        sectionKey = "completed",
                        group = group,
                        collapsed = collapsed,
                        onToggle = viewModel::toggleCollapsed,
                        completed = true,
                        onComplete = { viewModel.uncomplete(it) },
                        onDelete = ::requestDelete,
                        onClick = { onEditTask(it.template.id) }
                    )
                }
            }

            // ---------- 已作废区（为空时不显示） ----------
            if (data.expiredGroups.isNotEmpty()) {
                item(key = "expired_divider", contentType = "divider") {
                    Spacer(Modifier.padding(top = 8.dp))
                }
                sectionHeader("已作废", data.expiredGroups.sumOf { it.items.size }, "项")
                data.expiredGroups.forEach { group ->
                    group.items.forEach { item ->
                        item(
                            key = "expired_${group.category?.id ?: -1L}_${item.template.id}",
                            contentType = "expired_task"
                        ) {
                            SwipeableExpiredCard(
                                item = item,
                                color = group.color(),
                                onRevive = { viewModel.revive(item) },
                                onRequestDelete = { requestDelete(item) },
                                onClick = { onEditTask(item.template.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    // 删除确认弹窗：左滑露出「删除」按钮点击后才弹；勾选「不再提醒」则以后直接删
    pendingDelete?.let { item ->
        var dontRemind by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除清单？") },
            text = {
                Column {
                    Text("「${item.template.title}」及其历史记录、提醒将一并删除，无法恢复。")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { dontRemind = !dontRemind }
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = dontRemind, onCheckedChange = { dontRemind = it })
                        Text("不再提醒", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (dontRemind) viewModel.setDeleteConfirmEnabled(false)
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

private fun LazyListScope.sectionHeader(title: String, count: Int, unit: String) {
    item(key = "section_$title", contentType = "section_header") {
        Row(
            modifier = Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(8.dp))
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                Text(
                    text = "$count $unit",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
        }
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

    item(key = "header_$key", contentType = "category_header") {
        CategoryHeader(group = group, collapsed = isCollapsed, onToggle = { onToggle(key) })
    }

    item(key = "items_$key", contentType = "task_group") {
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
                .size(12.dp)
                .background(group.color(), RoundedCornerShape(4.dp))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = group.category?.let { "${it.icon} ${it.name}".trim() } ?: "未分类",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
        )
        Spacer(Modifier.width(8.dp))
        Surface(color = group.color().copy(alpha = 0.14f), shape = CircleShape) {
            Text(
                text = "${group.items.size}",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = group.color(),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = if (collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 滑动手势的锚点：收起 / 露出删除按钮 */
internal enum class SwipeAnchor { Closed, Revealed }

@OptIn(ExperimentalFoundationApi::class)
internal fun createSwipeRevealState(
    actionWidthPx: Float,
    velocityThresholdPx: () -> Float,
    decayAnimationSpec: DecayAnimationSpec<Float>
): AnchoredDraggableState<SwipeAnchor> {
    val anchors = DraggableAnchors<SwipeAnchor> {
        SwipeAnchor.Closed at 0f
        SwipeAnchor.Revealed at -actionWidthPx
    }
    return AnchoredDraggableState(
        initialValue = SwipeAnchor.Closed,
        anchors = anchors,
        positionalThreshold = { totalDistance -> totalDistance * 0.5f },
        velocityThreshold = velocityThresholdPx,
        snapAnimationSpec = tween(durationMillis = 200),
        decayAnimationSpec = decayAnimationSpec
    )
}

/**
 * 左滑露出「删除」按钮的操作行：滑动松手后停在露出态（不直接触发任何操作），
 * 点红色「删除」按钮才走删除流程——避免误滑完成/误滑删除。反向滑回收起。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeRevealDeleteBox(
    onRequestDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val actionWidth = 88.dp
    val actionWidthPx = with(density) { actionWidth.toPx() }
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    val state = remember(actionWidthPx, decay) {
        createSwipeRevealState(
            actionWidthPx = actionWidthPx,
            velocityThresholdPx = { with(density) { 200.dp.toPx() } },
            decayAnimationSpec = decay
        )
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        // 底层：右侧红色「删除」按钮，露出后才能点到
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(ExpiredRed, MaterialTheme.shapes.medium)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(actionWidth)
                    .fillMaxHeight()
                    .clickable {
                        scope.launch { state.animateTo(SwipeAnchor.Closed) }
                        onRequestDelete()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("删除", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
        // 上层：可左滑的内容卡片
        Box(
            modifier = Modifier
                .graphicsLayer { translationX = state.requireOffset() }
                .anchoredDraggable(state, Orientation.Horizontal)
        ) {
            content()
        }
    }
}

@Composable
private fun SwipeableTaskCard(
    item: TemplateWithInstance,
    color: Color,
    completed: Boolean,
    onComplete: () -> Unit,
    onRequestDelete: () -> Unit,
    onClick: () -> Unit
) {
    SwipeRevealDeleteBox(onRequestDelete = onRequestDelete) {
        TaskCard(
            item = item,
            categoryColor = color,
            onComplete = onComplete,
            onClick = onClick,
            completed = completed
        )
    }
}

@Composable
private fun SwipeableExpiredCard(
    item: TemplateWithInstance,
    color: Color,
    onRevive: () -> Unit,
    onRequestDelete: () -> Unit,
    onClick: () -> Unit
) {
    SwipeRevealDeleteBox(onRequestDelete = onRequestDelete) {
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
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = MaterialTheme.shapes.medium,
                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(40.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(color.copy(alpha = 0.45f), color.copy(alpha = 0.25f))
                        ),
                        RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)
                    )
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
