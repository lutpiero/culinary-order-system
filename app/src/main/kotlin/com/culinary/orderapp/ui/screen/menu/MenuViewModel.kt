package com.culinary.orderapp.ui.screen.menu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.culinary.orderapp.domain.model.Category
import com.culinary.orderapp.domain.model.MenuItem
import com.culinary.orderapp.domain.model.ToppingGroup
import com.culinary.orderapp.domain.usecase.DeleteMenuItemUseCase
import com.culinary.orderapp.domain.usecase.ObserveCategoriesUseCase
import com.culinary.orderapp.domain.usecase.ObserveMenuItemsUseCase
import com.culinary.orderapp.domain.usecase.SaveCategoryUseCase
import com.culinary.orderapp.domain.usecase.SaveMenuItemUseCase
import com.culinary.orderapp.domain.usecase.ToggleMenuItemAvailabilityUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MenuUiState(
    val categories: List<Category> = emptyList(),
    val menuItems: List<MenuItem> = emptyList(),
    val selectedCategoryId: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

data class MenuItemFormState(
    val item: MenuItem = MenuItem(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class MenuViewModel @Inject constructor(
    private val observeMenuItems: ObserveMenuItemsUseCase,
    private val observeCategories: ObserveCategoriesUseCase,
    private val saveMenuItem: SaveMenuItemUseCase,
    private val deleteMenuItem: DeleteMenuItemUseCase,
    private val toggleAvailability: ToggleMenuItemAvailabilityUseCase,
    private val saveCategory: SaveCategoryUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(MenuUiState(isLoading = true))
    val uiState: StateFlow<MenuUiState> = _uiState.asStateFlow()

    private val _formState = MutableStateFlow(MenuItemFormState())
    val formState: StateFlow<MenuItemFormState> = _formState.asStateFlow()

    init {
        loadCategories()
        loadMenuItems()
    }

    private fun loadCategories() {
        viewModelScope.launch {
            observeCategories()
                .catch { e ->
                    _uiState.value = _uiState.value.copy(errorMessage = e.message)
                }
                .collect { categories ->
                    _uiState.value = _uiState.value.copy(categories = categories)
                }
        }
    }

    fun loadMenuItems(categoryId: String? = null) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                selectedCategoryId = categoryId,
                errorMessage = null
            )
            observeMenuItems(categoryId)
                .catch { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = e.message
                    )
                }
                .collect { items ->
                    _uiState.value = _uiState.value.copy(menuItems = items, isLoading = false)
                }
        }
    }

    fun toggleAvailability(itemId: String, isAvailable: Boolean) {
        viewModelScope.launch {
            toggleAvailability.invoke(itemId, isAvailable)
        }
    }

    fun deleteItem(itemId: String) {
        viewModelScope.launch {
            val result = deleteMenuItem(itemId)
            _uiState.value = _uiState.value.copy(
                successMessage = if (result.isSuccess) "Item berhasil dihapus" else null,
                errorMessage = result.exceptionOrNull()?.message
            )
        }
    }

    fun loadItemForEdit(itemId: String?, allItems: List<MenuItem>) {
        val item = if (itemId != null) allItems.find { it.id == itemId } ?: MenuItem() else MenuItem()
        _formState.value = MenuItemFormState(item = item)
    }

    fun updateFormItem(item: MenuItem) {
        _formState.value = _formState.value.copy(item = item)
    }

    fun saveItem() {
        viewModelScope.launch {
            _formState.value = _formState.value.copy(isSaving = true, errorMessage = null)
            val result = saveMenuItem(_formState.value.item)
            _formState.value = _formState.value.copy(
                isSaving = false,
                errorMessage = result.exceptionOrNull()?.message
            )
        }
    }

    fun saveNewCategory(name: String) {
        viewModelScope.launch {
            val category = Category(name = name, displayOrder = _uiState.value.categories.size)
            saveCategory(category)
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(successMessage = null, errorMessage = null)
    }
}
