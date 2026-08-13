package com.gametodolist.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gametodolist.data.HomeData
import com.gametodolist.data.TemplateWithInstance
import com.gametodolist.data.TodoRepository
import com.gametodolist.sync.DataSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: TodoRepository,
    private val dataSync: DataSyncManager
) : ViewModel() {

    val homeData: StateFlow<HomeData> = repository.observeHomeData()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeData(emptyList(), emptyList()))

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun consumeError() { _errorMessage.value = null }

    private fun launchWrite(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() } catch (e: Exception) { _errorMessage.value = "操作失败，请重试" }
        }
    }

    init {
        launchWrite { dataSync.settleAndRefresh() }
    }

    /** 完成：任务不消失，划线后归入「已完成」区 */
    fun complete(item: TemplateWithInstance) {
        launchWrite {
            item.activeInstance?.let { repository.completeInstance(it.id) }
            dataSync.settleAndRefresh()
        }
    }

    /** 撤销完成：从「已完成」恢复回「未完成」 */
    fun uncomplete(item: TemplateWithInstance) {
        launchWrite {
            item.activeInstance?.let { repository.uncompleteInstance(it.id) }
            dataSync.settleAndRefresh()
        }
    }

    fun delete(item: TemplateWithInstance) {
        launchWrite {
            repository.deleteTemplate(item.template.id)
            dataSync.settleAndRefresh()
        }
    }
}
