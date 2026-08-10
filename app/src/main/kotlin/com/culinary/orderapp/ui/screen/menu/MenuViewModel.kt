package com.culinary.orderapp.ui.screen.menu

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.culinary.orderapp.domain.model.Category
import com.culinary.orderapp.domain.model.MenuItem
import com.culinary.orderapp.domain.model.ToppingGroup
import com.culinary.orderapp.domain.repository.StorageRepository
import com.culinary.orderapp.domain.usecase.DeleteMenuItemUseCase
import com.culinary.orderapp.domain.usecase.ObserveCategoriesUseCase
import com.culinary.orderapp.domain.usecase.ObserveMenuItemsUseCase
import com.culinary.orderapp.domain.usecase.ObserveSettingsUseCase
import com.culinary.orderapp.domain.usecase.SaveCategoryUseCase
import com.culinary.orderapp.domain.usecase.SaveMenuItemUseCase
import com.culinary.orderapp.domain.usecase.ToggleMenuItemAvailabilityUseCase
import com.culinary.orderapp.util.Logger
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
    val successMessage: String? = null,
    val businessName: String = "",
    val logoUrl: String? = null
)

data class MenuItemFormState(
    val item: MenuItem = MenuItem(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isUploadingImage: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class MenuViewModel @Inject constructor(
    private val observeMenuItems: ObserveMenuItemsUseCase,
    private val observeCategories: ObserveCategoriesUseCase,
    private val saveMenuItem: SaveMenuItemUseCase,
    private val deleteMenuItem: DeleteMenuItemUseCase,
    private val toggleAvailability: ToggleMenuItemAvailabilityUseCase,
    private val saveCategory: SaveCategoryUseCase,
    private val observeSettings: ObserveSettingsUseCase,
    private val storageRepository: StorageRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MenuUiState(isLoading = true))
    val uiState: StateFlow<MenuUiState> = _uiState.asStateFlow()

    private val _formState = MutableStateFlow(MenuItemFormState())
    val formState: StateFlow<MenuItemFormState> = _formState.asStateFlow()

    init {
        loadCategories()
        loadMenuItems()
        viewModelScope.launch {
            observeSettings().collect { settings ->
                if (settings != null) {
                    _uiState.value = _uiState.value.copy(
                        businessName = settings.businessName,
                        logoUrl = settings.logoUrl
                    )
                }
            }
        }
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
            this@MenuViewModel.toggleAvailability.invoke(itemId, isAvailable)
        }
    }

    fun deleteItem(itemId: String) {
        viewModelScope.launch {
            val item = _uiState.value.menuItems.find { it.id == itemId }
            item?.imageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                storageRepository.deleteImage(url)
                    .onFailure { Logger.w("Could not delete image $url: ${it.message}", TAG) }
            }
            val result = deleteMenuItem(itemId)
            _uiState.value = _uiState.value.copy(
                successMessage = if (result.isSuccess) "Item berhasil dihapus" else null,
                errorMessage = result.exceptionOrNull()?.message
            )
        }
    }

    private var loadedItemId: String? = null

    fun loadItemForEdit(itemId: String?, allItems: List<MenuItem>) {
        if (itemId == null) {
            if (loadedItemId == "new") return
            loadedItemId = "new"
            _formState.value = MenuItemFormState(item = MenuItem())
            return
        }
        if (loadedItemId == itemId) return
        val item = allItems.find { it.id == itemId } ?: return
        loadedItemId = itemId
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

    fun uploadMenuImage(uri: Uri) {
        viewModelScope.launch {
            _formState.value = _formState.value.copy(isUploadingImage = true, errorMessage = null)
            val itemId = _formState.value.item.id.ifBlank { "temp_${System.currentTimeMillis()}" }
            val result = storageRepository.uploadImage(uri, "menu_images/$itemId.jpg")
            result.fold(
                onSuccess = { url ->
                    _formState.value = _formState.value.copy(
                        item = _formState.value.item.copy(imageUrl = url),
                        isUploadingImage = false
                    )
                },
                onFailure = { e ->
                    _formState.value = _formState.value.copy(
                        isUploadingImage = false,
                        errorMessage = e.message ?: "Gagal mengunggah gambar"
                    )
                }
            )
        }
    }

    fun removeMenuImage() {
        viewModelScope.launch {
            val url = _formState.value.item.imageUrl
            if (url.isNotBlank()) {
                storageRepository.deleteImage(url)
                    .onFailure { Logger.w("Could not delete image $url: ${it.message}", TAG) }
            }
            _formState.value = _formState.value.copy(
                item = _formState.value.item.copy(imageUrl = "")
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

    fun saveToppingGroup(menuItemId: String, toppingGroup: ToppingGroup, isNew: Boolean) {
        viewModelScope.launch {
            val menuItem = _uiState.value.menuItems.find { it.id == menuItemId } ?: return@launch
            
            val updatedGroups = if (isNew) {
                menuItem.toppingGroups + toppingGroup
            } else {
                menuItem.toppingGroups.map { 
                    if (it.id == toppingGroup.id) toppingGroup else it 
                }
            }
            
            val updatedItem = menuItem.copy(toppingGroups = updatedGroups)
            saveMenuItem(updatedItem)
        }
    }

    fun deleteToppingGroup(menuItemId: String, toppingGroupId: String) {
        viewModelScope.launch {
            val menuItem = _uiState.value.menuItems.find { it.id == menuItemId } ?: return@launch
            val updatedGroups = menuItem.toppingGroups.filter { it.id != toppingGroupId }
            val updatedItem = menuItem.copy(toppingGroups = updatedGroups)
            saveMenuItem(updatedItem)
        }
    }

    companion object {
        private const val TAG = "MenuViewModel"
    }
}
