package com.gametodolist.ui.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gametodolist.data.entity.CategoryEntity
import com.gametodolist.data.entity.TaskTemplateEntity
import com.gametodolist.data.TodoRepository
import com.gametodolist.domain.RecurrenceType
import com.gametodolist.domain.RolloverManager
import com.gametodolist.domain.WindowMode
import com.gametodolist.domain.toRecurrenceType
import com.gametodolist.domain.toWindowMode
import com.gametodolist.sync.DataSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TaskEditUiState(
    val id: Long = 0,
    val title: String = "",
    val note: String = "",
    val categoryId: Long? = null,
    val recurrenceType: RecurrenceType = RecurrenceType.NONE,
    val intervalDays: Int = 1,
    val weekdaysMask: Int = 0,
    val monthDay: Int = 1,
    /** WEEKLY/MONTHLY：是否指定开始日期（未指定 = 立即开始，存 anchorDate=0） */
    val anchorEnabled: Boolean = false,
    /** 起始日期（本地零点毫秒）：NONE/INTERVAL_DAYS 始终使用；WEEKLY/MONTHLY 仅 anchorEnabled 时生效 */
    val anchorDate: Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
    val windowMode: WindowMode = WindowMode.FULL_CYCLE,
    /** CUSTOM 显示时段：从循环起点起显示的天数（1 = 显示 1 天，最小 1） */
    val customWindowDays: Int = 1,
    val reminderEnabled: Boolean = false,
    val reminderMinutesBefore: Int = 60,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class TaskEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: TodoRepository,
    private val rolloverManager: RolloverManager,
    private val dataSync: DataSyncManager
) : ViewModel() {

    private val templateId: Long = savedStateHandle.get<Long>("templateId") ?: -1L
    /** 从日历「+」进入时带入的默认开始日期（本地零点毫秒，<=0 表示未指定） */
    private val presetAnchorDate: Long = savedStateHandle.get<Long>("anchorDate") ?: -1L

    private val _uiState = MutableStateFlow(
        TaskEditUiState().let { s ->
            if (presetAnchorDate > 0) s.copy(anchorDate = presetAnchorDate, anchorEnabled = true) else s
        }
    )
    val uiState: StateFlow<TaskEditUiState> = _uiState.asStateFlow()

    val categories: StateFlow<List<CategoryEntity>> = repository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 加载完成时的快照，用于「放弃修改」脏检查 */
    private var snapshot: TaskEditUiState? = null

    init {
        if (templateId > 0) {
            viewModelScope.launch {
                repository.getTemplate(templateId)?.let { t ->
                    _uiState.update {
                        it.copy(
                            id = t.id,
                            title = t.title,
                            note = t.note,
                            categoryId = t.categoryId,
                            recurrenceType = t.recurrenceType.toRecurrenceType(),
                            intervalDays = t.intervalDays,
                            weekdaysMask = t.weekdaysMask,
                            monthDay = t.monthDay,
                            anchorEnabled = t.anchorDate > 0,
                            anchorDate = if (t.anchorDate > 0) t.anchorDate else it.anchorDate,
                            windowMode = t.windowMode.toWindowMode(),
                            customWindowDays = t.customWindowDays.coerceAtLeast(1),
                            reminderEnabled = t.reminderEnabled,
                            reminderMinutesBefore = t.reminderMinutesBefore
                        )
                    }
                    snapshot = _uiState.value
                }
            }
        } else {
            snapshot = _uiState.value
        }
    }

    fun update(transform: (TaskEditUiState) -> TaskEditUiState) = _uiState.update(transform)

    /** 与加载快照相比是否有未保存修改（忽略 saving/saved/errorMessage 等过程字段） */
    fun isDirty(): Boolean {
        val base = snapshot ?: return false
        fun TaskEditUiState.normalized() = copy(saving = false, saved = false, errorMessage = null)
        return _uiState.value.normalized() != base.normalized()
    }

    fun consumeError() = _uiState.update { it.copy(errorMessage = null) }

    fun save() {
        val s = _uiState.value
        if (s.title.isBlank() || s.saved || s.saving) return
        if (s.recurrenceType == RecurrenceType.WEEKLY && s.weekdaysMask == 0) return

        _uiState.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                // 分类可能已在管理页被删除：失效的 categoryId 归入未分类，避免任务从主页「隐身」
                val validCategoryId = s.categoryId
                    ?.takeIf { id -> categories.value.any { it.id == id } }
                val entity = TaskTemplateEntity(
                    id = s.id,
                    categoryId = validCategoryId,
                    title = s.title.trim(),
                    note = s.note.trim(),
                    recurrenceType = s.recurrenceType.name,
                    intervalDays = s.intervalDays.coerceAtLeast(1),
                    weekdaysMask = s.weekdaysMask,
                    monthDay = s.monthDay.coerceIn(1, 31),
                    anchorDate = when (s.recurrenceType) {
                        RecurrenceType.NONE, RecurrenceType.INTERVAL_DAYS -> s.anchorDate
                        else -> if (s.anchorEnabled) s.anchorDate else 0L
                    },
                    windowMode = s.windowMode.name,
                    customWindowDays = s.customWindowDays.coerceAtLeast(1),
                    reminderEnabled = s.reminderEnabled,
                    reminderMinutesBefore = s.reminderMinutesBefore
                )
                repository.saveTemplate(entity)
                if (s.id != 0L) {
                    rolloverManager.regenerateActiveInstance(entity.copy(id = s.id))
                }
                dataSync.settleAndRefresh()
                _uiState.update { it.copy(saved = true, saving = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(saving = false, errorMessage = "保存失败，请重试") }
            }
        }
    }

    /** 编辑模式下从编辑页直接删除（带确认，由 UI 弹窗后调用） */
    fun delete() {
        val s = _uiState.value
        if (s.id == 0L || s.saving || s.saved) return
        _uiState.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                repository.deleteTemplate(s.id)
                dataSync.settleAndRefresh()
                _uiState.update { it.copy(saved = true, saving = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(saving = false, errorMessage = "删除失败，请重试") }
            }
        }
    }
}
