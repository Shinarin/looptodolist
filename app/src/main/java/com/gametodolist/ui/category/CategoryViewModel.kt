package com.gametodolist.ui.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gametodolist.data.TodoRepository
import com.gametodolist.data.entity.CategoryEntity
import com.gametodolist.sync.DataSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CategoryViewModel @Inject constructor(
    private val repository: TodoRepository,
    private val dataSync: DataSyncManager
) : ViewModel() {

    val categories: StateFlow<List<CategoryEntity>> = repository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // 分类数据由 GameTodoApp 启动时播种，这里仅承载页面逻辑
    }

    fun save(category: CategoryEntity) {
        viewModelScope.launch {
            repository.upsertCategory(category)
            dataSync.settleAndRefresh()
        }
    }

    fun delete(category: CategoryEntity) {
        viewModelScope.launch {
            repository.deleteCategory(category)
            dataSync.settleAndRefresh()
        }
    }
}
