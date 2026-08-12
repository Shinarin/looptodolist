package com.gametodolist.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gametodolist.data.HomeData
import com.gametodolist.data.TemplateWithInstance
import com.gametodolist.data.TodoRepository
import com.gametodolist.domain.RolloverManager
import com.gametodolist.notify.ReminderScheduler
import com.gametodolist.widget.WidgetUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: TodoRepository,
    private val rolloverManager: RolloverManager,
    private val reminderScheduler: ReminderScheduler,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val homeData: StateFlow<HomeData> = repository.observeHomeData()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeData(emptyList(), emptyList()))

    init {
        // 进入主页先结算：作废过期任务并生成新循环
        viewModelScope.launch {
            rolloverManager.rolloverAll()
            WidgetUpdater.refresh(context)
        }
    }

    /** 完成：任务不消失，划线后归入「已完成」区 */
    fun complete(item: TemplateWithInstance) {
        viewModelScope.launch {
            item.activeInstance?.let { instance ->
                reminderScheduler.cancel(instance.id)
                repository.completeInstance(instance.id)
            }
            WidgetUpdater.refresh(context)
        }
    }

    /** 撤销完成：从「已完成」恢复回「未完成」 */
    fun uncomplete(item: TemplateWithInstance) {
        viewModelScope.launch {
            item.activeInstance?.let { instance ->
                repository.uncompleteInstance(instance.id)
                reminderScheduler.scheduleForInstance(instance.id)
            }
            WidgetUpdater.refresh(context)
        }
    }

    fun delete(item: TemplateWithInstance) {
        viewModelScope.launch {
            item.activeInstance?.let { reminderScheduler.cancel(it.id) }
            repository.deleteTemplate(item.template.id)
            WidgetUpdater.refresh(context)
        }
    }
}
