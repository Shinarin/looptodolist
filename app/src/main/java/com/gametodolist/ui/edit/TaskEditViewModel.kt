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
    val loaded: Boolean = false,
    val id: Long = 0,
    val title: String = "",
    val note: String = "",
    val categoryId: Long? = null,
    val recurrenceType: RecurrenceType = RecurrenceType.NONE,
    val intervalDays: Int = 1,
    val weekdaysMask: Int = 0,
    val monthDay: Int = 1,
    /** 起始日期（本地零点毫秒）：不循环 / 每 N 天 时使用，默认今天 */
    val anchorDate: Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
    val windowMode: WindowMode = WindowMode.FULL_CYCLE,
    /** CUSTOM 显示时段：从循环起点起显示的天数（1 = 显示 1 天，最小 1） */
    val customWindowDays: Int = 1,
    val reminderEnabled: Boolean = false,
    val reminderMinutesBefore: Int = 60,
    val saved: Boolean = false
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
            if (presetAnchorDate > 0) s.copy(anchorDate = presetAnchorDate) else s
        }
    )
    val uiState: StateFlow<TaskEditUiState> = _uiState.asStateFlow()

    val categories: StateFlow<List<CategoryEntity>> = repository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        if (templateId > 0) {
            viewModelScope.launch {
                repository.getTemplate(templateId)?.let { t ->
                    _uiState.update {
                        it.copy(
                            loaded = true,
                            id = t.id,
                            title = t.title,
                            note = t.note,
                            categoryId = t.categoryId,
                            recurrenceType = t.recurrenceType.toRecurrenceType(),
                            intervalDays = t.intervalDays,
                            weekdaysMask = t.weekdaysMask,
                            monthDay = t.monthDay,
                            anchorDate = if (t.anchorDate > 0) t.anchorDate else it.anchorDate,
                            windowMode = t.windowMode.toWindowMode(),
                            customWindowDays = t.customWindowDays.coerceAtLeast(1),
                            reminderEnabled = t.reminderEnabled,
                            reminderMinutesBefore = t.reminderMinutesBefore
                        )
                    }
                }
            }
        } else {
            _uiState.update { it.copy(loaded = true) }
        }
    }

    fun update(transform: (TaskEditUiState) -> TaskEditUiState) = _uiState.update(transform)

    fun save() {
        val s = _uiState.value
        if (s.title.isBlank() || s.saved) return
        if (s.recurrenceType == RecurrenceType.WEEKLY && s.weekdaysMask == 0) return

        viewModelScope.launch {
            val entity = TaskTemplateEntity(
                id = s.id,
                categoryId = s.categoryId,
                title = s.title.trim(),
                note = s.note.trim(),
                recurrenceType = s.recurrenceType.name,
                intervalDays = s.intervalDays.coerceAtLeast(1),
                weekdaysMask = s.weekdaysMask,
                monthDay = s.monthDay.coerceIn(1, 31),
                anchorDate = when (s.recurrenceType) {
                    RecurrenceType.NONE, RecurrenceType.INTERVAL_DAYS -> s.anchorDate
                    else -> 0L
                },
                windowMode = s.windowMode.name,
                customWindowDays = s.customWindowDays.coerceAtLeast(1),
                reminderEnabled = s.reminderEnabled,
                reminderMinutesBefore = s.reminderMinutesBefore
            )
            val newId = repository.saveTemplate(entity)
            if (s.id != 0L) {
                rolloverManager.regenerateActiveInstance(entity.copy(id = s.id))
            }
            dataSync.settleAndRefresh()
            _uiState.update { it.copy(saved = true) }
        }
    }
}
