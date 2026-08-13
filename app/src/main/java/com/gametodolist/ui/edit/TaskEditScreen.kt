package com.gametodolist.ui.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gametodolist.domain.RecurrenceType
import com.gametodolist.domain.WindowMode
import com.gametodolist.ui.WEEKDAY_LABELS
import com.gametodolist.ui.toFullDateText
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskEditScreen(
    onBack: () -> Unit,
    onManageCategories: () -> Unit,
    viewModel: TaskEditViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    var showDiscardDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    fun requestBack() {
        if (viewModel.isDirty()) showDiscardDialog = true else onBack()
    }

    BackHandler { requestBack() }

    LaunchedEffect(state.saved) { if (state.saved) onBack() }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (state.id == 0L) "新增清单" else "编辑清单") },
                navigationIcon = {
                    IconButton(onClick = { requestBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.id != 0L) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 点击非输入区域时取消输入状态
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { focusManager.clearFocus() })
                }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = state.title,
                onValueChange = { v -> viewModel.update { it.copy(title = v) } },
                label = { Text("标题") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.note,
                onValueChange = { v -> viewModel.update { it.copy(note = v) } },
                label = { Text("备注（可选）") },
                modifier = Modifier.fillMaxWidth()
            )

            // ---- 分类 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("分类")
                Spacer(Modifier.width(4.dp))
                // 分类旁的管理图标：单击进入分类管理
                Icon(
                    Icons.Outlined.Tune,
                    contentDescription = "分类管理",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable(onClick = onManageCategories)
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.categoryId == null,
                    onClick = { viewModel.update { it.copy(categoryId = null) } },
                    label = { Text("未分类") }
                )
                categories.forEach { cat ->
                    FilterChip(
                        selected = state.categoryId == cat.id,
                        onClick = { viewModel.update { it.copy(categoryId = cat.id) } },
                        label = { Text("${cat.icon} ${cat.name}".trim()) }
                    )
                }
            }

            // ---- 循环规则 ----
            SectionTitle("循环")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val types = RecurrenceType.entries
                val labels = listOf("不循环", "每 N 天", "每周", "每月")
                types.forEachIndexed { index, type ->
                    SegmentedButton(
                        selected = state.recurrenceType == type,
                        onClick = { viewModel.update { it.copy(recurrenceType = type) } },
                        shape = SegmentedButtonDefaults.itemShape(index, types.size)
                    ) { Text(labels[index]) }
                }
            }

            when (state.recurrenceType) {
                RecurrenceType.NONE -> DateField(
                    label = "开始日期",
                    dateMillis = state.anchorDate,
                    onPicked = { d -> viewModel.update { it.copy(anchorDate = d) } }
                )
                RecurrenceType.INTERVAL_DAYS -> {
                    NumberField(
                        label = "间隔天数",
                        value = state.intervalDays,
                        onValueChange = { v -> viewModel.update { it.copy(intervalDays = v) } }
                    )
                    DateField(
                        label = "开始日期",
                        dateMillis = state.anchorDate,
                        onPicked = { d -> viewModel.update { it.copy(anchorDate = d) } }
                    )
                }
                RecurrenceType.WEEKLY -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WEEKDAY_LABELS.forEachIndexed { index, label ->
                            val bit = 1 shl index
                            FilterChip(
                                selected = state.weekdaysMask and bit != 0,
                                onClick = {
                                    viewModel.update { it.copy(weekdaysMask = it.weekdaysMask xor bit) }
                                },
                                label = { Text("周$label") }
                            )
                        }
                    }
                    AnchorDateSection(state, viewModel)
                }
                RecurrenceType.MONTHLY -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    NumberField(
                        label = "每月第几日（短月自动取月末）",
                        value = state.monthDay,
                        onValueChange = { v -> viewModel.update { it.copy(monthDay = v) } }
                    )
                    AnchorDateSection(state, viewModel)
                }
            }

            // ---- 显示时段 ----
            SectionTitle("显示时段")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val fullLabel = if (state.recurrenceType == RecurrenceType.NONE) "长期有效" else "整个循环"
                SegmentedButton(
                    selected = state.windowMode == WindowMode.FULL_CYCLE,
                    onClick = { viewModel.update { it.copy(windowMode = WindowMode.FULL_CYCLE) } },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) { Text(fullLabel) }
                SegmentedButton(
                    selected = state.windowMode == WindowMode.CUSTOM,
                    onClick = { viewModel.update { it.copy(windowMode = WindowMode.CUSTOM) } },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text("自定义") }
            }

            if (state.windowMode == WindowMode.CUSTOM) {
                NumberField(
                    label = if (state.recurrenceType == RecurrenceType.NONE)
                        "持续天数（≥1，从开始日期起算）"
                    else
                        "显示天数（≥1，从每次循环开始日起算）",
                    value = state.customWindowDays,
                    onValueChange = { v ->
                        viewModel.update { it.copy(customWindowDays = v.coerceAtLeast(1)) }
                    }
                )
            }

            // ---- 提醒 ----
            if (state.recurrenceType != RecurrenceType.NONE) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("到期提醒", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "截止前 ${reminderLabel(state.reminderMinutesBefore)} 通知",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.reminderEnabled,
                        onCheckedChange = { v -> viewModel.update { it.copy(reminderEnabled = v) } }
                    )
                }
                if (state.reminderEnabled) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 60, 180, 1440).forEach { m ->
                            FilterChip(
                                selected = state.reminderMinutesBefore == m,
                                onClick = { viewModel.update { it.copy(reminderMinutesBefore = m) } },
                                label = { Text(reminderLabel(m)) }
                            )
                        }
                    }
                }
            }

            val canSave = state.title.isNotBlank() &&
                (state.recurrenceType != RecurrenceType.WEEKLY || state.weekdaysMask != 0)
            Button(
                onClick = viewModel::save,
                enabled = canSave && !state.saving,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (state.saving) "保存中…" else "保存") }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("放弃修改？") },
            text = { Text("当前修改尚未保存，离开后不会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onBack()
                }) { Text("放弃") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("继续编辑") }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除清单？") },
            text = { Text("「${state.title}」及其历史记录、提醒将一并删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.delete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun NumberField(label: String, value: Int, onValueChange: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { s ->
            s.filter { it.isDigit() }.toIntOrNull()?.let { onValueChange(it.coerceAtLeast(1)) }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

private fun reminderLabel(minutes: Int): String = when {
    minutes >= 1440 -> "${minutes / 1440} 天"
    minutes >= 60 -> "${minutes / 60} 小时"
    else -> "$minutes 分钟"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(
    label: String,
    dateMillis: Long,
    onPicked: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }

    androidx.compose.material3.OutlinedButton(
        onClick = { showDialog = true },
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                dateMillis.toFullDateText(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }

    if (showDialog) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = dateMillis)
        DatePickerDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { utcMillis ->
                        // DatePicker 返回 UTC 零点，转为本地零点
                        val localDate = Instant.ofEpochMilli(utcMillis)
                            .atZone(ZoneId.of("UTC")).toLocalDate()
                        onPicked(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
                    }
                    showDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("取消") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** WEEKLY/MONTHLY 的开始日期开关：未指定 = 立即开始（anchorDate 存 0，保持旧行为） */
@Composable
private fun AnchorDateSection(state: TaskEditUiState, viewModel: TaskEditViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("指定开始日期", style = MaterialTheme.typography.titleSmall)
                Text(
                    "首个循环不早于该日期；不指定则立即开始",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = state.anchorEnabled,
                onCheckedChange = { v -> viewModel.update { it.copy(anchorEnabled = v) } }
            )
        }
        if (state.anchorEnabled) {
            DateField(
                label = "开始日期",
                dateMillis = state.anchorDate,
                onPicked = { d -> viewModel.update { it.copy(anchorDate = d) } }
            )
        }
    }
}
