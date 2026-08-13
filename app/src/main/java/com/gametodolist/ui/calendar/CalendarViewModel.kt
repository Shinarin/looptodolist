package com.gametodolist.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gametodolist.data.TemplateWithInstance
import com.gametodolist.data.TodoRepository
import com.gametodolist.data.entity.CategoryEntity
import com.gametodolist.data.entity.TaskInstanceEntity
import com.gametodolist.data.entity.TaskTemplateEntity
import com.gametodolist.domain.InstanceStatus
import com.gametodolist.domain.RecurrenceEngine
import com.gametodolist.sync.DataSyncManager
import com.gametodolist.ui.toEpochMillis
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

/** 日历某天的清单条目：引擎计算的显示窗口 + 真实实例状态（若已生成） */
data class CalendarDayItem(
    val item: TemplateWithInstance,
    val windowStart: Long,
    val windowEnd: Long,
    val status: String,
    /** 所选日期恰为该清单的循环起点（当天更新） */
    val isRenewal: Boolean
)

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val repository: TodoRepository,
    private val dataSync: DataSyncManager
) : ViewModel() {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    private val templates: StateFlow<List<TaskTemplateEntity>> = repository.observeTemplates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val latestInstances: StateFlow<List<TaskInstanceEntity>> = repository.observeLatestInstances()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 当月「更新日」打点：日期 → 当天更新的清单的分类 id 列表 */
    val monthRenewals: StateFlow<Map<LocalDate, List<Long?>>> =
        combine(_month, templates) { ym, list ->
            val result = mutableMapOf<LocalDate, MutableList<Long?>>()
            list.forEach { t ->
                renewalDatesInMonth(ym, t).forEach { d ->
                    result.getOrPut(d) { mutableListOf() }.add(t.categoryId)
                }
            }
            result as Map<LocalDate, List<Long?>>
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * 所选日期的清单：用循环引擎计算每个模板在该日的显示窗口，
     * 窗口覆盖该日即显示；该日已有真实实例的取真实状态（完成/作废），
     * 未来日期则按规则预演，保证与数据对齐。
     */
    val dayItems: StateFlow<List<CalendarDayItem>> =
        combine(_selectedDate, templates, latestInstances) { date, list, latest ->
            val latestByTemplate = latest.associateBy { it.templateId }
            val dayStart = date.toEpochMillis()
            list.mapNotNull { t ->
                runCatching {
                    val cycle = RecurrenceEngine.currentCycleStartDate(date, t, zone)
                    val window = RecurrenceEngine.computeWindow(cycle, t, zone)
                    if (dayStart < window.startMillis || dayStart >= window.endMillis) return@runCatching null
                    val real = latestByTemplate[t.id]?.takeIf {
                        Instant.ofEpochMilli(it.cycleStart).atZone(zone).toLocalDate() == cycle
                    }
                    CalendarDayItem(
                        item = TemplateWithInstance(t, real),
                        windowStart = window.startMillis,
                        windowEnd = window.endMillis,
                        status = real?.status ?: InstanceStatus.ACTIVE.name,
                        isRenewal = cycle == date
                    )
                }.getOrNull()
            }.sortedBy { it.windowEnd }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> = repository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { dataSync.settleAndRefresh() }
    }

    /** 模板在当月内的所有循环起点（更新日）；不循环的取开始日期 */
    private fun renewalDatesInMonth(ym: YearMonth, t: TaskTemplateEntity): List<LocalDate> =
        runCatching {
            val monthStart = ym.atDay(1)
            val monthEnd = ym.atEndOfMonth()
            val result = mutableListOf<LocalDate>()
            var cycle = RecurrenceEngine.currentCycleStartDate(monthStart, t, zone)
            var guard = 0
            while (cycle <= monthEnd && guard++ < 400) {
                if (cycle >= monthStart) result.add(cycle)
                val next = RecurrenceEngine.nextCycleStartDate(cycle, t)
                if (next == cycle) break // NONE：起点即唯一起点
                cycle = next
            }
            result
        }.getOrDefault(emptyList())

    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
    }

    fun prevMonth() {
        _month.value = _month.value.minusMonths(1)
    }

    fun nextMonth() {
        _month.value = _month.value.plusMonths(1)
    }

    fun categoryColor(categoryId: Long?): Long? =
        categories.value.firstOrNull { it.id == categoryId }?.color
}
