package com.culinary.orderapp.domain.usecase

import com.culinary.orderapp.domain.model.Category
import com.culinary.orderapp.domain.model.MenuItem
import com.culinary.orderapp.domain.repository.MenuRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveMenuItemsUseCase @Inject constructor(
    private val menuRepository: MenuRepository
) {
    operator fun invoke(categoryId: String? = null): Flow<List<MenuItem>> =
        menuRepository.observeMenuItems(categoryId)
}

class ObserveCategoriesUseCase @Inject constructor(
    private val menuRepository: MenuRepository
) {
    operator fun invoke(): Flow<List<Category>> =
        menuRepository.observeCategories()
}

class SaveMenuItemUseCase @Inject constructor(
    private val menuRepository: MenuRepository
) {
    suspend operator fun invoke(item: MenuItem): Result<MenuItem> =
        menuRepository.saveMenuItem(item)
}

class DeleteMenuItemUseCase @Inject constructor(
    private val menuRepository: MenuRepository
) {
    suspend operator fun invoke(id: String): Result<Unit> =
        menuRepository.deleteMenuItem(id)
}

class ToggleMenuItemAvailabilityUseCase @Inject constructor(
    private val menuRepository: MenuRepository
) {
    suspend operator fun invoke(id: String, isAvailable: Boolean): Result<Unit> =
        menuRepository.toggleMenuItemAvailability(id, isAvailable)
}

class SaveCategoryUseCase @Inject constructor(
    private val menuRepository: MenuRepository
) {
    suspend operator fun invoke(category: Category): Result<Category> =
        menuRepository.saveCategory(category)
}
