package com.gametodolist.ui.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gametodolist.data.TodoRepository
import com.gametodolist.data.entity.CategoryEntity
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
class CategoryViewModel @Inject constructor(
    private val repository: TodoRepository,
    private val dataSync: DataSyncManager
) : ViewModel() {

    val categories: StateFlow<List<CategoryEntity>> = repository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun consumeError() { _errorMessage.value = null }

    fun save(category: CategoryEntity) {
        viewModelScope.launch {
            try {
                repository.upsertCategory(category)
                dataSync.settleAndRefresh()
            } catch (e: Exception) {
                _errorMessage.value = "保存失败，请重试"
            }
        }
    }

    fun delete(category: CategoryEntity) {
        viewModelScope.launch {
            try {
                repository.deleteCategory(category)
                dataSync.settleAndRefresh()
            } catch (e: Exception) {
                _errorMessage.value = "删除失败，请重试"
            }
        }
    }
}
